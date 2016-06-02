package net.sandius.rembulan.core;

import net.sandius.rembulan.util.Check;
import net.sandius.rembulan.util.Cons;

import java.util.Iterator;

/*
 Properties:

   1) For any coroutine c,
         (c.resuming == null || c.resuming.yieldingTo == c) && (c.yieldingTo == null || c.yieldingTo.resuming == c)
         (i.e. coroutines form a doubly-linked list)

   2) if c is the currently-running coroutine, then c.resuming == null

   3) if c is the main coroutine, then c.yieldingTo == null

 Coroutine d can be resumed from c iff
   c != d && d.resuming == null && d.yieldingTo == null

 This means that
   c.resuming = d
   d.yieldingTo = c
 */
public final class Coroutine {

	// paused call stack: up-to-date only iff coroutine is not running
	private Cons<ResumeInfo> callStack;

	private Coroutine yieldingTo;
	private Coroutine resuming;

	public Coroutine(Function function) {
		this.callStack = new Cons<>(new ResumeInfo(BootstrapResumable.INSTANCE, Check.notNull(function)));
		this.yieldingTo = null;
		this.resuming = null;
	}

	public boolean isPaused() {
		return callStack != null;
	}

	public boolean isResuming() {
		return resuming != null;
	}

	public boolean isDead() {
		return callStack == null;
	}

	public boolean canYield() {
		return yieldingTo != null;
	}

	@Override
	public String toString() {
		return "thread: 0x" + Integer.toHexString(hashCode());
	}

	protected static class BootstrapResumable implements Resumable {

		static final BootstrapResumable INSTANCE = new BootstrapResumable();

		@Override
		public Preemption resume(ExecutionContext context, Object suspendedState) {
			Function target = (Function) suspendedState;

			// become the target
			return Dispatch.call(context, target, context.getObjectSink().toArray());
		}

	}

	// FIXME: name clash
	private Coroutine resume(Coroutine target) {
		Check.notNull(target);

		synchronized (this) {
			synchronized (target) {

				if (target.callStack == null) {
					// dead coroutine
					throw new IllegalStateException("cannot resume dead coroutine");
				}
				else if (target == this || target.resuming != null) {
					// running or normal coroutine
					throw new IllegalStateException("cannot resume non-suspended coroutine");
				}
				else {
					target.yieldingTo = this;
					this.resuming = target;

					return target;
				}
			}
		}
	}

	private Coroutine yield() {
		synchronized (this) {
			Coroutine target = this.yieldingTo;

			if (target != null) {
				synchronized (target) {  // FIXME: unsafe: target may have been changed already!

					assert (this.resuming == null);
					assert (target.resuming == this);

					this.yieldingTo = null;
					target.resuming = null;

					return target;
				}
			}
			else {
				return null;
			}
		}
	}

	private void saveFrames(Preemption ct) {
		Iterator<ResumeInfo> it = ct.frames();
		while (it.hasNext()) {
			callStack = new Cons<>(it.next(), callStack);
		}
	}

	public ResumeResult resume(ExecutionContext context, Throwable error) {
		Check.isNull(resuming);

		while (callStack != null) {
			ResumeInfo top = callStack.car;
			callStack = callStack.cdr;

			Preemption p = null;
			try {
				if (error == null) {
					// no errors
					p = top.resume(context);
					p = p == null ? Dispatch.evaluateTailCalls(context) : p;
				}
				else {
					// there is an error to be handled
					if (top.resumable instanceof ProtectedResumable) {
						// top is protected, can handle the error
						Throwable e = error;
						error = null;  // this exception will be handled

						ProtectedResumable pr = (ProtectedResumable) top.resumable;

						p = pr.resumeError(context, top.savedState, Conversions.throwableToObject(e));
						p = p == null ? Dispatch.evaluateTailCalls(context) : p;
					}
					else {
						// top is not protected, continue unwinding the stack
					}
				}
			}
			catch (Exception ex) {
				// unhandled exception: will try finding a handler in the next iteration
				error = ex;
			}

			// process the preemption
			if (p != null) {

				if (p instanceof Preemption.Pause) {
					saveFrames(p);
					assert (callStack != null);
					return ResumeResult.Pause.INSTANCE;
				}
				else if (p instanceof Preemption.CoroutineSwitch.Yield) {
					saveFrames(p);

					Preemption.CoroutineSwitch.Yield yield = (Preemption.CoroutineSwitch.Yield) p;

					Coroutine c = this.yield();
					if (c != null) {
						context.getObjectSink().setToArray(yield.arguments());
						return new ResumeResult.Switch(c);
					}
					else {
						error = new IllegalOperationAttemptException("attempt to yield from outside a coroutine");
					}
				}
				else if (p instanceof Preemption.CoroutineSwitch.Resume) {
					saveFrames(p);

					Preemption.CoroutineSwitch.Resume resume = (Preemption.CoroutineSwitch.Resume) p;

					final Coroutine c;
					try {
						c = this.resume(resume.target());
						context.getObjectSink().setToArray(resume.arguments());
						return new ResumeResult.Switch(c);
					}
					catch (Exception ex) {
						error = ex;
					}
				}
				else {
					throw new UnsupportedOperationException(p.toString());
				}

			}

		}

		assert (callStack == null);

		Coroutine yieldTarget = yield();
		if (yieldTarget != null) {
			return new ResumeResult.ImplicitYield(yieldTarget, error);
		}
		else {
			// main coroutine return
			return error == null ? ResumeResult.Finished.INSTANCE : new ResumeResult.Error(error);
		}
	}

}
