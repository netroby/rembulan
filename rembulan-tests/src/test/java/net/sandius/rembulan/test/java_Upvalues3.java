package net.sandius.rembulan.test;

import net.sandius.rembulan.core.Dispatch;
import net.sandius.rembulan.core.ExecutionContext;
import net.sandius.rembulan.core.Preemption;
import net.sandius.rembulan.core.Upvalue;
import net.sandius.rembulan.core.impl.DefaultSavedState;
import net.sandius.rembulan.core.impl.Function0;

public class java_Upvalues3 extends Function0 {

	protected final Upvalue _ENV;

	public java_Upvalues3(Upvalue _ENV) {
		super();
		this._ENV = _ENV;
	}

	private Preemption run(ExecutionContext context, int rp, Object r_0, Object r_1, Object r_2) {
		switch (rp) {
			case 0:
				r_0 = null;
				r_1 = null;
				rp = 1;
				{
					Preemption p;
					p = Dispatch.index(context, _ENV.get(), "g");
					if (p != null) {
						p.push(this, snapshot(rp, r_0, r_1, r_2));
						return p;
					}
				}
			case 1:
				r_2 = context.getObjectSink()._0();
				if (r_2 == null) {
					r_0 = context.getState().newUpvalue(r_0);
					r_2 = new f1((Upvalue) r_0);
					r_1 = r_2;
					r_1 = context.getState().newUpvalue(r_1);
				}
				else {
					r_1 = context.getState().newUpvalue(r_1);
					r_2 = new f2((Upvalue) r_1);
					r_0 = r_2;
					r_0 = context.getState().newUpvalue(r_0);
				}
				if (((Upvalue) r_0).get() != null) {
					r_2 = ((Upvalue) r_1).get();
				}
				else {
					r_2 = ((Upvalue) r_0).get();
				}
				context.getObjectSink().setTo(r_2);
				return null;

			default:
				throw new IllegalStateException();
		}
	}

	private Object snapshot(int rp, Object r_0, Object r_1, Object r_2) {
		return new DefaultSavedState(rp, new Object[] { r_0, r_1, r_2 });
	}

	@Override
	public Preemption resume(ExecutionContext context, Object suspendedState) {
		DefaultSavedState ss = (DefaultSavedState) suspendedState;
		Object[] regs = ss.registers();
		return run(context, ss.resumptionPoint(), regs[0], regs[1], regs[2]);
	}

	@Override
	public Preemption invoke(ExecutionContext context) {
		return run(context, 0, null, null, null);
	}

	public static class f1 extends Function0 {

		protected final Upvalue x;

		public f1(Upvalue x) {
			super();
			this.x = x;
		}

		private Preemption run(ExecutionContext context, int rp, Object r_0, Object r_1) {
			r_0 = x.get();
			context.getObjectSink().setTo(r_0);
			return null;
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Preemption invoke(ExecutionContext context) {
			return run(context, 0, null, null);
		}

	}

	public static class f2 extends Function0 {

		protected final Upvalue y;

		public f2(Upvalue y) {
			super();
			this.y = y;
		}

		private Preemption run(ExecutionContext context, int rp, Object r_0, Object r_1) {
			r_0 = y.get();
			context.getObjectSink().setTo(r_0);
			return null;
		}

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Preemption invoke(ExecutionContext context) {
			return run(context, 0, null, null);
		}

	}


}
