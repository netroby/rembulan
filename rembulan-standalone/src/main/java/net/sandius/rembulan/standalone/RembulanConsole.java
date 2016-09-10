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

package net.sandius.rembulan.standalone;

import jline.console.ConsoleReader;
import net.sandius.rembulan.Conversions;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.Variable;
import net.sandius.rembulan.compiler.CompilerChunkLoader;
import net.sandius.rembulan.compiler.CompilerSettings;
import net.sandius.rembulan.env.RuntimeEnvironment;
import net.sandius.rembulan.env.RuntimeEnvironments;
import net.sandius.rembulan.exec.CallException;
import net.sandius.rembulan.exec.CallInterruptedException;
import net.sandius.rembulan.exec.DirectCallExecutor;
import net.sandius.rembulan.lib.impl.DefaultBasicLib;
import net.sandius.rembulan.lib.impl.StdLibConfig;
import net.sandius.rembulan.load.LoaderException;
import net.sandius.rembulan.runtime.DefaultLuaState;
import net.sandius.rembulan.runtime.LuaFunction;
import net.sandius.rembulan.util.Check;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

public class RembulanConsole {

	private final CommandLineArguments config;

	private final InputStream in;
	private final PrintStream out;
	private final PrintStream err;

//	private final LuaState state;
	private final Table env;

	private final CompilerChunkLoader loader;

	private int chunkIndex;

	private final LuaFunction requireFunction;

	private final boolean javaTraceback;
	private final boolean stackTraceForCompileErrors;
	private final String[] tracebackSuppress;

	private final DirectCallExecutor callExecutor;

	public RembulanConsole(CommandLineArguments cmdLineArgs, InputStream in, PrintStream out, PrintStream err) {

		javaTraceback = System.getenv(Constants.ENV_FULL_TRACEBACK) != null;
		tracebackSuppress = new String[] {
				"net.sandius.rembulan.core",
				this.getClass().getName()
		};
		stackTraceForCompileErrors = javaTraceback;

		this.config = Check.notNull(cmdLineArgs);

		this.in = Check.notNull(in);
		this.out = Check.notNull(out);
		this.err = Check.notNull(err);

		CompilerSettings.CPUAccountingMode cpuAccountingMode =
				System.getenv(Constants.ENV_CPU_ACCOUNTING) != null
				? CompilerSettings.CPUAccountingMode.IN_EVERY_BASIC_BLOCK
				: CompilerSettings.CPUAccountingMode.NO_CPU_ACCOUNTING;
		CompilerSettings compilerSettings = CompilerSettings
				.defaultSettings()
				.withCPUAccountingMode(cpuAccountingMode);

		DefaultLuaState state = new DefaultLuaState();
		this.loader = CompilerChunkLoader.of(compilerSettings, "rembulan_repl_");
		RuntimeEnvironment runtimeEnv = RuntimeEnvironments.systemRuntimeEnvironment(in, out, err);
		this.env = StdLibConfig.of(runtimeEnv)
				.withLoader(loader)
				.setDebug(true)
				.installInto(state);

		this.callExecutor = DirectCallExecutor.newExecutor(state);

		// command-line arguments
		env.rawset("arg", cmdLineArgs.toArgTable(state));

		{
			// get the require function
			Object req = env.rawget("require");
			requireFunction = req instanceof LuaFunction ? (LuaFunction) req : null;
		}
	}

	private void printVersion() {
		out.println("Rembulan " + Constants.VERSION
				+ " ("
				+ System.getProperty("java.vm.name")
				+ ", Java "
				+ System.getProperty("java.version")
				+ ")");
	}

	private static void printUsage(PrintStream out) {
		String programName = "rembulan";

		out.println("usage: " + programName + " [options] [script [args]]");
		out.println("Available options are:");
		out.println("  -e stat  execute string 'stat'");
		out.println("  -i       enter interactive mode after executing 'script'");
		out.println("  -l name  require library 'name'");
		out.println("  -v       show version information");
		out.println("  -E       ignore environment variables");
		out.println("  --       stop handling options");
		out.println("  -        stop handling options and execute stdin");
	}

	private Object[] callFunction(LuaFunction fn, Object... args)
			throws CallException {

		try {
			return callExecutor.call(fn, args);
		}
		catch (CallInterruptedException | InterruptedException ex) {
			throw new CallException(ex);
		}
	}

	private void executeProgram(String sourceText, String sourceFileName, String[] args)
			throws LoaderException, CallException {

		Check.notNull(sourceText);
		Check.notNull(sourceFileName);
		Check.notNull(args);

		LuaFunction fn = loader.loadTextChunk(new Variable(env), sourceFileName, sourceText);

		Object[] callArgs = new Object[args.length];
		System.arraycopy(args, 0, callArgs, 0, args.length);

		callFunction(fn, callArgs);
	}

	private void executeFile(String fileName, String[] args)
			throws LoaderException, CallException  {

		Check.notNull(fileName);
		final String source;
		try {
			source = Utils.readFile(fileName);
		}
		catch (IOException ex) {
			throw new LoaderException(ex, fileName);
		}

		executeProgram(Utils.skipLeadingShebang(source), fileName, Check.notNull(args));
	}

	private void executeStdin(String[] args)
			throws LoaderException, CallException  {

		final String source;
		try {
			source = Utils.readInputStream(in);
		}
		catch (IOException ex) {
			throw new LoaderException(ex, Constants.SOURCE_STDIN);
		}

		executeProgram(Utils.skipLeadingShebang(source), Constants.SOURCE_STDIN, Check.notNull(args));
	}

	private void requireModule(String moduleName) throws CallException {
		callFunction(requireFunction, Check.notNull(moduleName));
	}

	private void execute(LuaFunction fn) throws CallException {
		Object[] results = callFunction(fn);
		if (results.length > 0) {
			callFunction(new DefaultBasicLib.Print(out), results);
		}
	}

	public boolean start() throws IOException {
		try {
			for (CommandLineArguments.Step step : config.steps()) {
				executeStep(step);
			}
		}
		catch (CallException ex) {
			if (!javaTraceback) {
				ex.printLuaFormatStackTraceback(err, loader.getChunkClassLoader(), tracebackSuppress);
			}
			else {
				ex.printStackTrace(err);
			}
			return false;
		}
		catch (LoaderException ex) {
			if (!stackTraceForCompileErrors) {
				err.println(ex.getLuaStyleErrorMessage());
			}
			else {
				ex.printStackTrace(err);
			}
			return false;
		}

		if (config.interactive()) {
			startInteractive();
		}

		return true;
	}

	private void executeStep(CommandLineArguments.Step s)
			throws LoaderException, CallException  {

		Check.notNull(s);

		switch (s.what()) {

			case PRINT_VERSION:
				printVersion();
				break;

			case EXECUTE_STRING:
				executeProgram(s.arg0(), s.arg1(), new String[0]);
				break;

			case EXECUTE_FILE:
				executeFile(s.arg0(), s.argArray());
				break;

			case EXECUTE_STDIN:
				executeStdin(s.argArray());
				break;

			case REQUIRE_MODULE:
				requireModule(s.arg0());
				break;

		}
	}

	private String getPrompt() {
		String s = Conversions.stringValueOf(env.rawget(Constants.VAR_NAME_PROMPT));
		return s != null ? s : Constants.DEFAULT_PROMPT;
	}

	private String getPrompt2() {
		String s = Conversions.stringValueOf(env.rawget(Constants.VAR_NAME_PROMPT2));
		return s != null ? s : Constants.DEFAULT_PROMPT2;
	}

	private void startInteractive() throws IOException {
		ConsoleReader reader = new ConsoleReader(in, out);

		reader.setExpandEvents(false);

		String line;
		StringBuilder codeBuffer = new StringBuilder();
		reader.setPrompt(getPrompt());

		while ((line = reader.readLine()) != null) {
			out.print("");

			LuaFunction fn = null;

			boolean firstLine = codeBuffer.length() == 0;
			boolean emptyInput = line.trim().isEmpty();

			if (firstLine && !emptyInput) {
				try {
					fn = loader.loadTextChunk(new Variable(env), Constants.SOURCE_STDIN, "return " + line);
				}
				catch (LoaderException ex) {
					// ignore
				}
			}

			if (fn == null) {
				codeBuffer.append(line).append('\n');
				try {
					fn = loader.loadTextChunk(new Variable(env), Constants.SOURCE_STDIN, codeBuffer.toString());
				}
				catch (LoaderException ex) {
					if (ex.isPartialInputError()) {
						// partial input
						reader.setPrompt(getPrompt2());
					}
					else {
						// faulty input
						if (!stackTraceForCompileErrors) {
							err.println(ex.getLuaStyleErrorMessage());
						}
						else {
							ex.printStackTrace(err);
						}

						// reset back to initial state
						codeBuffer.setLength(0);
						reader.setPrompt(getPrompt());
					}
				}
			}

			if (fn != null) {
				// reset back to initial state
				codeBuffer.setLength(0);

				try {
					execute(fn);
				}
				catch (CallException ex) {
					if (!javaTraceback) {
						ex.printLuaFormatStackTraceback(err, loader.getChunkClassLoader(), tracebackSuppress);
					}
					else {
						ex.printStackTrace(err);
					}
				}

				reader.setPrompt(getPrompt());
			}
		}
	}

	public static void main(String[] args) {
		// Caveat: inTty == true iff stdin *and* stdout are tty; however we only care about stdin
		boolean inTty = System.console() != null;

		CommandLineArguments cmdLineArgs = null;
		try {
			cmdLineArgs = CommandLineArguments.parseArguments(args, inTty);
		}
		catch (IllegalArgumentException ex) {
			System.err.println(ex.getMessage());
			printUsage(System.err);
			System.exit(1);
		}

		assert (cmdLineArgs != null);

		RembulanConsole console = new RembulanConsole(cmdLineArgs, System.in, System.out, System.err);

		int rc;
		try {
			rc = console.start() ? 1 : 0;
		}
		catch (Exception ex) {
			System.err.println("Encountered fatal error (aborting):");
			ex.printStackTrace(System.err);
			rc = 1;
		}

		System.exit(rc);
	}

}
