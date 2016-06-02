package net.sandius.rembulan.bm;

import net.sandius.rembulan.core.ControlThrowable;
import net.sandius.rembulan.core.Dispatch;
import net.sandius.rembulan.core.ExecutionContext;
import net.sandius.rembulan.core.Invokable;
import net.sandius.rembulan.core.ObjectSink;
import net.sandius.rembulan.core.Preemption;
import net.sandius.rembulan.core.alt.AltOperators;
import net.sandius.rembulan.core.impl.Function2;
import net.sandius.rembulan.core.impl.Function3;
import net.sandius.rembulan.core.impl.QuintupleCachingObjectSink;
import net.sandius.rembulan.core.legacy.Operators;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Warmup;

import static net.sandius.rembulan.util.testing.Assertions.assertEquals;

@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 20)
public class CallReturnBenchmark {

	public static ObjectSink newSink() {
		return new QuintupleCachingObjectSink();
	}

	public long primitiveMethod(long n, long l) {
		if (l > 0) {
			return primitiveMethod(n, l - 1) + 1;
		}
		else {
			return n;
		}
	}

	public Object objectMethod(Object n, Object l) {
		long ll = ((Number) l).longValue();
		if (ll > 0) {
			return ((Number) objectMethod(n, ll - 1)).longValue() + 1;
		}
		else {
			return n;
		}
	}

	public Number numberObjectMethod(Number n, Number l) {
		long ll = l.longValue();
		if (ll > 0) {
			return numberObjectMethod(n, ll - 1).longValue() + 1;
		}
		else {
			return n;
		}
	}

	public Long longObjectMethod(Long n, Long l) {
		long ll = l;
		if (ll > 0) {
			return longObjectMethod(n, ll - 1) + 1;
		}
		else {
			return n;
		}
	}

	public static abstract class JavaPrimitiveTwoArgFuncObject {
		public abstract long call(JavaPrimitiveTwoArgFuncObject arg1, long arg2);
	}

	public static class JavaPrimitiveTwoArgFuncObjectImpl extends JavaPrimitiveTwoArgFuncObject {

		private final long n;

		public JavaPrimitiveTwoArgFuncObjectImpl(long n) {
			this.n = n;
		}

		@Override
		public long call(JavaPrimitiveTwoArgFuncObject f, long l) {
			if (l > 0) {
				return f.call(f, l - 1) + 1;
			}
			else {
				return n;
			}
		}

	}

	public static abstract class JavaTwoArgFuncObject {
		public abstract Object call(Object arg1, Object arg2);
	}

	public static class JavaTwoArgFuncObjectImpl extends JavaTwoArgFuncObject {

		private final Long n;

		public JavaTwoArgFuncObjectImpl(long n) {
			this.n = n;
		}

		@Override
		public Object call(Object arg1, Object arg2) {
			JavaTwoArgFuncObject f = (JavaTwoArgFuncObject) arg1;
			long l = ((Number) arg2).longValue();

			if (l > 0) {
				Object r = f.call(f, l - 1);
				return ((Number)r).longValue() + 1;
			}
			else {
				return n;
			}
		}

	}

	public static abstract class JavaVarargFuncObject {
		public abstract Object call(Object... args);
	}

	public static class JavaVarargFuncObjectImpl extends JavaVarargFuncObject {

		private final Long n;

		public JavaVarargFuncObjectImpl(long n) {
			this.n = n;
		}

		@Override
		public Object call(Object... args) {
			if (args.length < 2) {
				throw new IllegalArgumentException();
			}

			JavaVarargFuncObject f = (JavaVarargFuncObject) args[0];
			long l = ((Number) args[1]).longValue();

			if (l > 0) {
				return ((Number) f.call(f, l - 1)).longValue() + 1;
			}
			else {
				return n;
			}
		}

	}


	@Benchmark
	public void _0_0_primitiveMethod() {
		long result = primitiveMethod(100, 20);
		assertEquals(result, 120L);
	}

	@Benchmark
	public void _0_1_objectMethod() {
		Object result = objectMethod(100L, 20L);
		assertEquals(result, 120L);
	}

	@Benchmark
	public void _0_2_numberObjectMethod() {
		Number result = numberObjectMethod(100L, 20L);
		assertEquals(result, 120L);
	}

	@Benchmark
	public void _0_3_longObjectMethod() {
		Long result = longObjectMethod(100L, 20L);
		assertEquals(result, 120L);
	}

	@Benchmark
	public void _0_4_javaPrimitiveFunctionObject() {
		JavaPrimitiveTwoArgFuncObject f = new JavaPrimitiveTwoArgFuncObjectImpl(100);
		long result = f.call(f, 20);
		assertEquals(result, 120L);
	}

	@Benchmark
	public void _0_5_javaGenericTwoArgFunctionObject() {
		JavaTwoArgFuncObject f = new JavaTwoArgFuncObjectImpl(100);
		Object result = f.call(f, 20);
		assertEquals(result, 120L);
	}

	@Benchmark
	public void _0_6_javaVarargFunctionObject() {
		JavaVarargFuncObject f = new JavaVarargFuncObjectImpl(100);
		Object result = f.call(f, 20);
		assertEquals(result, 120L);
	}


	public static class RecursiveInvokeFunc extends Function2 {

		private final Long n;

		public RecursiveInvokeFunc(long n) {
			this.n = n;
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2) {
			Invokable f = (Invokable) arg1;
			long l = ((Number) arg2).longValue();
			if (l > 0) {
				f.invoke(context, f, l - 1);
				Number m = (Number) context.getObjectSink()._0();
				context.getObjectSink().setTo(m.longValue() + 1);
				return null;
			}
			else {
				context.getObjectSink().setTo(n);
				return null;
			}
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	public static class RecursiveCallFunc extends Function2 {

		private final Long n;

		public RecursiveCallFunc(long n) {
			this.n = n;
		}

		private Preemption run(ExecutionContext context, int pc, Object r_0, Object r_1) {
			switch (pc) {
				case 0:
				case 1:
					long l = ((Number) r_1).longValue();
					if (l > 0) {
						{
							Preemption p = Dispatch.call(context, r_0, r_0, l - 1);
							if (p != null) {
								p.push(this, null);
								return p;
							}
						}
						Number m = (Number) context.getObjectSink()._0();
						context.getObjectSink().setTo(m.longValue() + 1);
						return null;
					}
					else {
						context.getObjectSink().setTo(n);
						return null;
					}
				default:
					throw new IllegalStateException();
			}
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2) {
			return run(context, 0, arg1, arg2);
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	public static class TailCallFunc extends Function3 {

		private final Long n;

		public TailCallFunc(long n) {
			this.n = n;
		}

		private void run(ExecutionContext context, int pc, Object r_0, Object r_1, Object r_2) {
			switch (pc) {
				case 0:
				case 1:
					long l = ((Number) r_1).longValue();
					long acc = ((Number) r_2).longValue();
					if (l > 0) {
						context.getObjectSink().tailCall(r_0, r_0, l - 1, acc + 1);
					}
					else {
						context.getObjectSink().setTo(acc + n);
					}
			}
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2, Object arg3) {
			run(context, 0, arg1, arg2, arg3);
			return null;
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	public static class SelfRecursiveTailCallFunc extends Function2 {

		private final Long n;

		public SelfRecursiveTailCallFunc(long n) {
			this.n = n;
		}

		private void run(ExecutionContext context, int pc, Object r_0, Object r_1) {
			switch (pc) {
				case 0:
				case 1:
					long l = ((Number) r_0).longValue();
					long acc = ((Number) r_1).longValue();
					if (l > 0) {
						context.getObjectSink().tailCall(this, l - 1, acc + 1);
					}
					else {
						context.getObjectSink().setTo(acc + n);
					}
			}
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2) {
			run(context, 0, arg1, arg2);
			return null;
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	@Benchmark
	public void _1_1_recursiveInvoke(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new RecursiveInvokeFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		f.invoke(context, f, 20);
		assertEquals(result._0(), 120L);
	}

	@Benchmark
	public void _1_2_recursiveCall(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new RecursiveCallFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		Dispatch.call(context, f, f, 20);
		assertEquals(result._0(), 120L);
	}

	@Benchmark
	public void _1_3_tailCall(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new TailCallFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		Dispatch.call(context, f, f, 20, 0);
		assertEquals(result._0(), 120L);
	}

	@Benchmark
	public void _1_4_selfRecursiveTailCall(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new SelfRecursiveTailCallFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		Dispatch.call(context, f, 20, 0);
		assertEquals(result._0(), 120L);
	}

	public static class RecursiveOpCallFunc extends Function2 {

		private final Long n;

		private static final Long k_0 = 0L;
		private static final Long k_1 = 1L;

		public RecursiveOpCallFunc(long n) {
			this.n = n;
		}

		private void run(ExecutionContext context, int pc, Object r_0, Object r_1) throws ControlThrowable {
			switch (pc) {
				case 0:
				case 1:
					if (Operators.lt(context.getState(), k_0, r_1)) {
						r_1 = Operators.sub(context.getState(), r_1, k_1);
						Dispatch.call(context, r_0, r_0, r_1);
						r_0 = context.getObjectSink()._0();
						r_0 = Operators.add(context.getState(), r_0, k_1);
						context.getObjectSink().setTo(r_0);
					}
					else {
						context.getObjectSink().setTo(n);
					}
			}
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2) {
			try {
				run(context, 0, arg1, arg2);
				return null;
			}
			catch (ControlThrowable ct) {
				return ct.toPreemption();
			}
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	public static class RecursiveAltOpCallFunc extends Function2 {

		private final Long n;

		private static final Long k_0 = 0L;
		private static final Long k_1 = 1L;

		public RecursiveAltOpCallFunc(long n) {
			this.n = n;
		}

		private void run(ExecutionContext context, int pc, Object r_0, Object r_1) throws ControlThrowable {
			switch (pc) {
				case 0:
				case 1:
					if (AltOperators.lt(context.getState(), k_0, r_1)) {
						r_1 = AltOperators.sub(context.getState(), r_1, k_1);
						Dispatch.call(context, r_0, r_0, r_1);
						r_0 = context.getObjectSink()._0();
						r_0 = AltOperators.add(context.getState(), r_0, k_1);
						context.getObjectSink().setTo(r_0);
					}
					else {
						context.getObjectSink().setTo(n);
					}
			}
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2) {
			try {
				run(context, 0, arg1, arg2);
				return null;
			}
			catch (ControlThrowable ct) {
				return ct.toPreemption();
			}
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	public static class TailCallOpFunc extends Function3 {

		private final Long n;

		private static final Long k_0 = 0L;
		private static final Long k_1 = 1L;

		public TailCallOpFunc(long n) {
			this.n = n;
		}

		private void run(ExecutionContext context, int pc, Object r_0, Object r_1, Object r_2) {
			switch (pc) {
				case 0:
				case 1:
					if (Operators.gt(context.getState(), r_1, k_0)) {
						r_1 = Operators.sub(context.getState(), r_1, k_1);
						r_2 = Operators.add(context.getState(), r_2, k_1);
						context.getObjectSink().tailCall(r_0, r_0, r_1, r_2);
					}
					else {
						r_0 = Operators.add(context.getState(), r_2, n);
						context.getObjectSink().setTo(r_0);
					}
			}
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2, Object arg3) {
			run(context, 0, arg1, arg2, arg3);
			return null;
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	public static class TailCallAltOpFunc extends Function3 {

		private final Long n;

		private static final Long k_0 = 0L;
		private static final Long k_1 = 1L;

		public TailCallAltOpFunc(long n) {
			this.n = n;
		}

		private void run(ExecutionContext context, int pc, Object r_0, Object r_1, Object r_2) {
			switch (pc) {
				case 0:
				case 1:
					if (AltOperators.gt(context.getState(), r_1, k_0)) {
						r_1 = AltOperators.sub(context.getState(), r_1, k_1);
						r_2 = AltOperators.add(context.getState(), r_2, k_1);
						context.getObjectSink().tailCall(r_0, r_0, r_1, r_2);
					}
					else {
						r_0 = AltOperators.add(context.getState(), r_2, n);
						context.getObjectSink().setTo(r_0);
					}
			}
		}

		@Override
		public Preemption invoke(ExecutionContext context, Object arg1, Object arg2, Object arg3) {
			run(context, 0, arg1, arg2, arg3);
			return null;
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

	}

	@Benchmark
	public void _2_2_0_recursiveOpCall(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new RecursiveOpCallFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		Dispatch.call(context, f, f, 20);
		assertEquals(result._0(), 120L);
	}

	@Benchmark
	public void _2_2_1_recursiveAltOpCall(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new RecursiveAltOpCallFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		Dispatch.call(context, f, f, 20L);
		assertEquals(result._0(), 120L);
	}

	@Benchmark
	public void _2_3_0_tailOpCall(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new TailCallOpFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		Dispatch.call(context, f, f, 20, 0);
		assertEquals(result._0(), 120L);
	}

	@Benchmark
	public void _2_3_1_tailAltOpCall(DummyLuaState luaState) throws ControlThrowable {
		Invokable f = new TailCallAltOpFunc(100);
		ObjectSink result = newSink();
		ExecutionContext context = new DummyExecutionContext(luaState, result);
		Dispatch.call(context, f, f, 20, 0);
		assertEquals(result._0(), 120L);
	}

}
