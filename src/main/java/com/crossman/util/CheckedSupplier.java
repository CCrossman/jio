package com.crossman.util;

@FunctionalInterface
public interface CheckedSupplier<T extends Throwable, A> {
	public A get() throws T;
}
