package net.sandius.rembulan;

public abstract class ObjectSink {

	public abstract int size();

	public abstract void reset();

	public abstract void push(Object o);

	public abstract Object[] toArray();

	public abstract Object get(int idx);

	public abstract Object _0();

	public abstract Object _1();

}