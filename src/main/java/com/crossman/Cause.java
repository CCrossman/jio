package com.crossman;

import java.util.Objects;
import java.util.function.Function;

public final class Cause<E> {
	private static final Cause<Void> interrupted = new Cause<>(null, new InterruptedException());

	private final E error;
	private final Throwable cause;

	public Cause(E error) {
		this(error,null);
	}

	public Cause(E error, Throwable cause) {
		this.error = error;
		this.cause = cause;
	}

	public E getError() {
		return error;
	}

	public Throwable getCause() {
		return cause;
	}

	@SuppressWarnings("unchecked")
	public <F> Cause<F> map(Function<E,F> fn) {
		if (error == null) {
			return (Cause<F>)this;
		}
		return new Cause<>(fn.apply(error),cause);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Cause<?> cause1 = (Cause<?>) o;
		return Objects.equals(getError(), cause1.getError()) &&
				Objects.equals(getCause(), cause1.getCause());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getError(), getCause());
	}

	@Override
	public String toString() {
		return "Cause{" +
				"error=" + error +
				", cause=" + cause +
				'}';
	}

	@SuppressWarnings("unchecked")
	public static <E> Cause<E> interrupted() {
		return (Cause<E>)interrupted;
	}
}
