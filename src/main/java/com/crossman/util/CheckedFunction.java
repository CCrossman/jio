package com.crossman.util;

@FunctionalInterface
public interface CheckedFunction<T extends Throwable, A, B> {
	public B apply(A a) throws T;
}
