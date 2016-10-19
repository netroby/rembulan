/*
 * Copyright 2016 Miroslav Janíček
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sandius.rembulan.lib;

import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.ByteStringBuilder;
import net.sandius.rembulan.Conversions;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.env.RuntimeEnvironment;
import net.sandius.rembulan.impl.UnimplementedFunction;
import net.sandius.rembulan.load.ChunkLoader;
import net.sandius.rembulan.load.LoaderException;
import net.sandius.rembulan.runtime.Dispatch;
import net.sandius.rembulan.runtime.ExecutionContext;
import net.sandius.rembulan.runtime.LuaFunction;
import net.sandius.rembulan.runtime.ResolvedControlThrowable;
import net.sandius.rembulan.runtime.UnresolvedControlThrowable;
import net.sandius.rembulan.util.ByteIterator;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The package library provides basic facilities for loading modules in Lua. It exports
 * one function directly in the global environment: {@code require}.
 * Everything else is exported in a {@code table} package.
 */
public final class ModuleLib {

	static final byte PATH_SEPARATOR = (byte) ';';
	static final byte PATH_TEMPLATE_PLACEHOLDER = (byte) '?';
	static final byte WIN_DIRECTORY_PLACEHOLDER = (byte) '!';  // FIXME: not used in Rembulan
	static final byte LUAOPEN_IGNORE = (byte) '-';  // FIXME: not used in Rembulan!

	private ModuleLib() {
		// not to be instantiated
	}

	public static void installInto(StateContext context, Table env, RuntimeEnvironment runtimeEnvironment, ChunkLoader chunkLoader, ClassLoader classLoader) {
		Objects.requireNonNull(context);
		Objects.requireNonNull(env);
		Objects.requireNonNull(runtimeEnvironment);
		Objects.requireNonNull(classLoader);

		FileSystem fileSystem = runtimeEnvironment.fileSystem();

		Table t = context.newTable();

		final ByteString config;
		Table loaded = context.newTable();
		Table preload = context.newTable();
		final ByteString path;
		Table searchers = context.newTable();
		LuaFunction searchpath = new SearchPath(fileSystem);
		LuaFunction require = new Require(t, loaded);

		// package.config
		{
			ByteStringBuilder builder = new ByteStringBuilder();

			builder.append(fileSystem.getSeparator()).append((byte) '\n');
			builder.append(PATH_SEPARATOR).append((byte) '\n');
			builder.append(PATH_TEMPLATE_PLACEHOLDER).append((byte) '\n');
			builder.append(WIN_DIRECTORY_PLACEHOLDER).append((byte) '\n');
			builder.append(LUAOPEN_IGNORE).append((byte) '\n');

			config = builder.toByteString();
		}

		// package.loaded
		{
			loaded.rawset("_G", env);
			loaded.rawset("package", t);
		}

		// package.path
		{
			String envPath = runtimeEnvironment.getEnv("LUA_PATH_5_3");
			if (envPath == null) {
				envPath = runtimeEnvironment.getEnv("LUA_PATH");
			}

			path = getPath(envPath, defaultPath(runtimeEnvironment.fileSystem()));
		}

		// package.searchers
		{
			addSearcher(searchers, new PreloadSearcher(preload));
			if (chunkLoader != null) {
				addSearcher(searchers, new ChunkLoadPathSearcher(fileSystem, t, chunkLoader, env));
			}
			addSearcher(searchers, new LoaderProviderServiceLoaderSearcher(runtimeEnvironment, env, classLoader));
		}

		t.rawset("config", config);
		t.rawset("loaded", loaded);
		t.rawset("loadlib", new UnimplementedFunction("package.loadlib"));
		t.rawset("preload", preload);
		t.rawset("searchers", searchers);
		t.rawset("searchpath", searchpath);
		t.rawset("path", path);

		// install into the global environment
		env.rawset("package", t);
		env.rawset("require", require);
	}

	static void addSearcher(Table searchers, LuaFunction fn) {
		searchers.rawset(searchers.rawlen() + 1, fn);
	}

	static Table searchers(Table libTable) {
		Object o = libTable.rawget("searchers");
		return o instanceof Table ? (Table) o : null;
	}

	static ByteString defaultPath(FileSystem fileSystem) {
		// TODO: make this depend on the platform
		return ByteString.of("/usr/local/share/lua/5.3/?.lua;/usr/local/share/lua/5.3/?/init.lua;/usr/local/lib/lua/5.3/?.lua;/usr/local/lib/lua/5.3/?/init.lua;./?.lua;./?/init.lua");
	}

	static ByteString getPath(String envPath, ByteString defaultPath) {
		if (envPath != null) {
			return ByteString.of(envPath).replace(ByteString.of(";;"), defaultPath);
		}
		else {
			return defaultPath;
		}
	}

	static class Require extends AbstractLibFunction {

		private final Table libTable;
		private final Table loaded;

		private static class Require_SuspendedState {

			private final int state;
			private final ByteString error;
			private final ByteString modName;
			private final Table searchers;
			private final long idx;

			private Require_SuspendedState(int state, ByteString error, ByteString modName, Table searchers, long idx) {
				this.state = state;
				this.error = error;
				this.modName = modName;
				this.searchers = searchers;
				this.idx = idx;
			}

		}

		public Require(Table libTable, Table loaded) {
			this.libTable = Objects.requireNonNull(libTable);
			this.loaded = Objects.requireNonNull(loaded);
		}

		@Override
		protected String name() {
			return "require";
		}

		@Override
		protected void invoke(ExecutionContext context, ArgumentIterator args) throws ResolvedControlThrowable {
			ByteString modName = args.nextString();

			Object mod = loaded.rawget(modName);

			if (mod != null) {
				// already loaded
				context.getReturnBuffer().setTo(mod);
			}
			else {
				// get package.searchers
				Table searchers = searchers(libTable);
				if (searchers == null) {
					throw new IllegalStateException("'package.searchers' must be a table");
				}
				search(context, 0, ByteString.empty(), modName, searchers, 1);
			}
		}

		private void search(ExecutionContext context, int state, ByteString error, ByteString modName, Table searchers, long idx)
				throws ResolvedControlThrowable {

			final LuaFunction loader;
			final Object origin;

			loop:
			while (true) {
				try {
					switch (state) {
						case 0:
							Object o = searchers.rawget(idx++);
							if (o == null) {
								// reached the end of the list
								throw new LuaRuntimeException("module '" + modName + "' not found:" + error);
							}
							state = 1;
							Dispatch.call(context, o, modName);

						case 1:
							Object result = context.getReturnBuffer().get0();
							if (result instanceof LuaFunction) {
								// found it
								loader = (LuaFunction) result;
								origin = context.getReturnBuffer().get1();

								break loop;
							}
							else {
								// not a loader

								// append error string
								ByteString s = Conversions.stringValueOf(result);
								if (s != null) {
									error = error.concat(s);
								}

								state = 0;  // continue with the next iteration
								break;
							}

						default:
							throw new IllegalStateException("Invalid state: " + state);
					}
				}
				catch (UnresolvedControlThrowable ct) {
					throw ct.resolve(this, new Require_SuspendedState(state, error, modName, searchers, idx));
				}
			}

			load(context, modName, loader, origin);
		}

		private void load(ExecutionContext context, ByteString modName, LuaFunction loader, Object origin)
				throws ResolvedControlThrowable {

			try {
				Dispatch.call(context, loader, modName, origin);
			}
			catch (UnresolvedControlThrowable ct) {
				throw ct.resolve(this, modName);
			}

			resumeLoad(context, modName);
		}

		private void resumeLoad(ExecutionContext context, ByteString modName) {
			Object loadResult = context.getReturnBuffer().get0();
			Object requireResult = loadResult != null ? loadResult : true;

			loaded.rawset(modName, requireResult);
			context.getReturnBuffer().setTo(requireResult);
		}

		@Override
		public void resume(ExecutionContext context, Object suspendedState) throws ResolvedControlThrowable {
			if (suspendedState instanceof Require_SuspendedState) {
				Require_SuspendedState ss = (Require_SuspendedState) suspendedState;
				search(context, ss.state, ss.error, ss.modName, ss.searchers, ss.idx);
			}
			else {
				resumeLoad(context, (ByteString) suspendedState);
			}
		}

	}

	static class PreloadSearcher extends AbstractLibFunction {

		private final Table preload;

		PreloadSearcher(Table preload) {
			this.preload = Objects.requireNonNull(preload);
		}

		@Override
		protected String name() {
			return "(preload searcher)";
		}

		@Override
		protected void invoke(ExecutionContext context, ArgumentIterator args) throws ResolvedControlThrowable {
			ByteString modName = args.nextString();

			Object entry = preload.rawget(modName);

			if (entry != null) {
				context.getReturnBuffer().setTo(entry);
			}
			else {
				String error = "\n\tno field package.preload['" + modName + "']";
				context.getReturnBuffer().setTo(error);
			}
		}

	}

	static class ChunkLoadPathSearcher extends AbstractLibFunction {

		private final Table libTable;
		private final FileSystem fileSystem;
		private final ChunkLoader loader;
		private final Object env;

		ChunkLoadPathSearcher(FileSystem fileSystem, Table libTable, ChunkLoader loader, Object env) {
			this.fileSystem = Objects.requireNonNull(fileSystem);
			this.libTable = Objects.requireNonNull(libTable);
			this.loader = Objects.requireNonNull(loader);
			this.env = env;
		}

		@Override
		protected String name() {
			return "(path searcher)";
		}

		private LuaFunction loaderForPath(ByteString path) throws LoaderException {
			return BasicLib.loadTextChunkFromFile(fileSystem, loader, path.toString(), BasicLib.Load.DEFAULT_MODE, env);
		}

		@Override
		protected void invoke(ExecutionContext context, ArgumentIterator args) throws ResolvedControlThrowable {
			ByteString modName = args.nextString();

			// FIXME: it might be easier and more modular to just call package.searchpath and loadfile

			ByteString path = Conversions.stringValueOf(libTable.rawget("path"));
			if (path == null) {
				throw new IllegalStateException("'package.path' must be a string");
			}

			List<ByteString> paths = SearchPath.getPaths(modName, path, SearchPath.DEFAULT_SEP, ByteString.of(fileSystem.getSeparator()));

			ByteStringBuilder msgBuilder = new ByteStringBuilder();
			for (ByteString s : paths) {
				Path p = fileSystem.getPath(s.toString());
				if (Files.isReadable(p)) {
					final LuaFunction fn;
					try {
						fn = loaderForPath(s);
					}
					catch (LoaderException ex) {
						throw new LuaRuntimeException("error loading module '" + modName + "' from file '" + s + "'"
								+ "\n\t" + ex.getLuaStyleErrorMessage());
					}

					context.getReturnBuffer().setTo(fn, s);
					return;
				}
				else {
					msgBuilder.append("\n\tno file '").append(s).append((byte) '\'');
				}
			}

			context.getReturnBuffer().setTo(msgBuilder.toByteString());
		}

	}

	/**
	 * An abstract searcher function that uses a {@link ServiceLoader} to discover
	 * loader services.
	 *
	 * @param <T>  the type of the loader service
	 */
	static abstract class AbstractServiceLoaderSearcher<T> extends AbstractLibFunction {

		private final Class<T> serviceClass;
		private final ServiceLoader<T> serviceLoader;

		protected AbstractServiceLoaderSearcher(Class<T> serviceClass, ClassLoader classLoader) {
			this.serviceClass = Objects.requireNonNull(serviceClass);
			this.serviceLoader = ServiceLoader.load(serviceClass, classLoader);
		}

		@Override
		protected String name() {
			return "(" + serviceClass.getName() + " ServiceLoader searcher)";
		}

		private LuaFunction findLoader(String modName) {
			try {
				for (T service : serviceLoader) {
					if (matches(modName, service)) {
						LuaFunction loader = getLoader(service);
						if (loader != null) {
							return loader;
						}
					}
				}
			}
			catch (ServiceConfigurationError error) {
				// TODO: maybe we should just let the VM crash?
				throw new LuaRuntimeException(error);
			}

			// not found
			return null;
		}

		/**
		 * Returns {@code} true if the given service {@code service} provides a module with
		 * the name {@code moduleName}.
		 *
		 * @param moduleName  module name
		 * @param service  a service to be examined
		 *
		 * @return  {@code true} if the {@code service} provides a module with the name
		 *          {@code moduleName}
		 */
		protected abstract boolean matches(String moduleName, T service);

		/**
		 * Returns the loader provided by a given service.
		 *
		 * <p>May return {@code null} to indicate that {@code service} does not provide
		 * a loader.</p>
		 *
		 * @param service  the service to get the loader from
		 * @return  the loader function
		 */
		protected abstract LuaFunction getLoader(T service);

		@Override
		protected void invoke(ExecutionContext context, ArgumentIterator args) throws ResolvedControlThrowable {
			ByteString modName = args.nextString();

			LuaFunction loader = findLoader(modName.toString());

			if (loader != null) {
				context.getReturnBuffer().setTo(loader);
			}
			else {
				String error = "\n\tno " + serviceClass.getName() + " for '" + modName + "'";
				context.getReturnBuffer().setTo(error);
			}

		}

	}

	static class LoaderProviderServiceLoaderSearcher extends AbstractServiceLoaderSearcher<LoaderProvider> {

		private final RuntimeEnvironment runtimeEnvironment;
		private final Table env;

		public LoaderProviderServiceLoaderSearcher(RuntimeEnvironment runtimeEnvironment, Table env, ClassLoader classLoader) {
			super(LoaderProvider.class, classLoader);
			this.runtimeEnvironment = Objects.requireNonNull(runtimeEnvironment);
			this.env = Objects.requireNonNull(env);
		}

		@Override
		protected boolean matches(String modName, LoaderProvider service) {
			return service.name().equals(modName);
		}

		@Override
		protected LuaFunction getLoader(LoaderProvider service) {
			return service.newLoader(runtimeEnvironment, env);
		}

	}

	static class SearchPath extends AbstractLibFunction {

		private static final ByteString DEFAULT_SEP = ByteString.constOf(".");

		private final FileSystem fileSystem;
		private final ByteString defaultDirSeparator;

		SearchPath(FileSystem fileSystem) {
			this.fileSystem = Objects.requireNonNull(fileSystem);
			defaultDirSeparator = ByteString.of(fileSystem.getSeparator());
		}

		@Override
		protected String name() {
			return "searchpath";
		}

		static List<ByteString> getPaths(ByteString name, ByteString path, ByteString sep, ByteString rep) {
			List<ByteString> result = new ArrayList<>();

			name = name.replace(sep, rep);

			ByteStringBuilder builder = new ByteStringBuilder();
			ByteIterator it = path.byteIterator();
			while (it.hasNext()) {
				byte b = it.nextByte();
				switch (b) {
					case PATH_TEMPLATE_PLACEHOLDER:
						builder.append(name);
						break;
					case PATH_SEPARATOR:
						result.add(builder.toByteString());
						builder.setLength(0);
						break;
					default:
						builder.append(b);
						break;
				}
			}

			if (builder.length() > 0) {
				result.add(builder.toByteString());
			}

			return result;
		}

		@Override
		protected void invoke(ExecutionContext context, ArgumentIterator args) throws ResolvedControlThrowable {
			ByteString name = args.nextString();
			ByteString path = args.nextString();

			ByteString sep = args.hasNext() && args.peek() != null ? args.nextString() : DEFAULT_SEP;
			ByteString rep = args.hasNext() && args.peek() != null ? args.nextString() : defaultDirSeparator;

			ByteStringBuilder msgBuilder = new ByteStringBuilder();

			for (ByteString s : getPaths(name, path, sep, rep)) {
				Path p = fileSystem.getPath(s.toString());
				if (Files.isReadable(p)) {
					context.getReturnBuffer().setTo(s);
					return;
				}
				else {
					msgBuilder.append("\n\tno file '").append(s).append((byte) '\'');
				}
			}

			// no readable file found
			context.getReturnBuffer().setTo(null, msgBuilder.toString());
		}

	}

}