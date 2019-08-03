package com.crossman;

import com.crossman.util.CheckedFunction;
import com.crossman.util.CheckedSupplier;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.*;

public abstract class Jio<R,E,A> {

	// sealed type
	private Jio() {}

	public final <B extends A> Jio<R,Void,A> catchAll(Function<E, Jio<R,Void,B>> fn) {
		return this.foldM(e -> fn.apply(e).map(b -> (A)b).mapError($ -> (E)null), a -> Jio.success(a)).mapError(e -> (Void)null);
	}

	public final <EE extends E, B> Jio<R,E,B> flatMap(Function<A, Jio<R,EE,B>> fn) {
		return this.foldM(e -> Jio.fail(e), a -> fn.apply(a));
	}

	public final <Z> Jio<R,E,Z> fold(Function<E,Z> onFail, Function<A,Z> onPass) {
		return foldM(e -> Jio.success(onFail.apply(e)), a -> Jio.success(onPass.apply(a)));
	}

	public abstract <E1 extends E, E2 extends E, Z> Jio<R,E,Z> foldM(Function<E, Jio<R,E1,Z>> onFail, Function<A, Jio<R,E2,Z>> onPass);

	public final <B> Jio<R,E,B> map(Function<A,B> fn) {
		return flatMap(a -> Jio.success(fn.apply(a)));
	}

	public abstract <F> Jio<R,F,A> mapError(Function<E,F> fn);
	public abstract void unsafeRun(R r, BiConsumer<E,A> blk);

	public final <EE extends E, B, Z> Jio<R,E,Z> zip(Jio<R,EE,B> that, BiFunction<A,B,Z> fn) {
		return flatMap(a -> that.map(b -> fn.apply(a,b)));
	}

	public final <EE extends E, B> Jio<R,E,A> zipLeft(Jio<R,EE,B> that) {
		return zip(that, (a,b) -> a);
	}

	public final <EE extends E, B> Jio<R,E,B> zipRight(Jio<R,EE,B> that) {
		return zip(that, (a,b) -> b);
	}

	/**
	 * constructs a Jio instance that runs its effect(s) every
	 * time the unsafeRun() method is called.
	 *
	 * @param aSupplier the value supplier that has side effects
	 * @param <R>       the Environment type
	 * @param <E>       the Failure type
	 * @param <A>       the Value type
	 * @return          a Jio instance
	 */
	public static <R,E,A> Jio<R,E,A> effect(Supplier<A> aSupplier) {
		return new EvalAlways<>(new Supplier<Jio<R,E,A>>() {
			@Override
			public Jio<R, E, A> get() {
				A a = aSupplier.get();
				return Jio.success(a);
			}
		});
	}

	/**
	 * constructs a Jio instance that captures an asynchronous callback
	 *
	 * @param cc    a callback that accepts a callback that accepts a Jio
	 * @param <R>   the Environment type
	 * @param <E>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	public static <R,E,A> Jio<R,E,A> effectAsync(Consumer<Consumer<Jio<R,E,A>>> cc) {
		final Promise<R,E,A> p = new Promise<>();
		cc.accept(p::setDelegate);
		return p;
	}

	/**
	 * constructs a failing Jio instance
	 *
	 * @param error the failure
	 * @param <R>   the Environment type
	 * @param <E>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	public static <R,E,A> Jio<R,E,A> fail(E error) {
		return new Failure<>(error);
	}

	/**
	 * constructs a Jio instance atop a fallible function
	 *
	 * @param cf    the fallible function
	 * @param <R>   the Environment type
	 * @param <T>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	@SuppressWarnings("unchecked")
	public static <R,T extends Throwable,A> Jio<R,T,A> fromFunction(CheckedFunction<T,R,A> cf) {
		return new SinkAndSource<>((r, taBiConsumer) -> {
			try {
				A a = cf.apply(r);
				taBiConsumer.accept(null, a);
			} catch (Throwable t) {
				taBiConsumer.accept((T) t, null);
			}
		});
	}

	/**
	 * constructs a Jio instance from a CompletionStage
	 *
	 * @param completionStage   the CompletionStage
	 * @param <R>               the Environment type
	 * @param <A>               the Value type
	 * @return                  a Jio instance
	 */
	public static <R,A> Jio<R,CompletionException,A> fromFuture(CompletionStage<A> completionStage) {
		final Promise<R,CompletionException,A> p = new Promise<>();

		completionStage.whenCompleteAsync((a,t) -> {
			if (t != null) {
				p.setDelegate(Jio.fail(new CompletionException(t)));
			} else {
				p.setDelegate(Jio.success(a));
			}
		});

		return p;
	}

	/**
	 * constructs a Jio instance from a CompletionStage
	 *
	 * @param executor          the Executor running the async code
	 * @param completionStage   the CompletionStage
	 * @param <R>               the Environment type
	 * @param <A>               the Value type
	 * @return                  a Jio instance
	 */
	public static <R,A> Jio<R,CompletionException,A> fromFuture(Executor executor, CompletionStage<A> completionStage) {
		final Promise<R,CompletionException,A> p = new Promise<>();

		completionStage.whenCompleteAsync((a,t) -> {
			if (t != null) {
				p.setDelegate(Jio.fail(new CompletionException(t)));
			} else {
				p.setDelegate(Jio.success(a));
			}
		}, executor);

		return p;
	}

	/**
	 * constructs a Jio instance that succeeds or fails
	 *
	 * @param blk   the value supplier, which may fail
	 * @param <R>   the Environment type
	 * @param <T>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	@SuppressWarnings("unchecked")
	public static <R,T extends Throwable,A> Jio<R,T,A> fromTrying(CheckedSupplier<T,A> blk) {
		try {
			A a = blk.get();
			return success(a);
		} catch (Throwable t) {
			return fail((T)t);
		}
	}

	/**
	 * constructs an incomplete Jio instance
	 *
	 * @param <R>   the Environment type
	 * @param <E>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	public static <R,E,A> Promise<R,E,A> promise() {
		return new Promise<>();
	}

	/**
	 * constructs a successful Jio instance
	 *
	 * @param value the value
	 * @param <R>   the Environment type
	 * @param <E>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	public static <R,E,A> Jio<R,E,A> success(A value) {
		return new Success<>(value);
	}

	/**
	 * constructs a successful Jio instance lazily
	 *
	 * @param aSupplier a value supplier
	 * @param <R>       the Environment type
	 * @param <E>       the Failure type
	 * @param <A>       the Value type
	 * @return          a Jio instance
	 */
	public static <R,E,A> Jio<R,E,A> successLazy(Supplier<A> aSupplier) {
		return successLazy(Executors.newSingleThreadExecutor(), aSupplier);
	}

	/**
	 * constructs a successful Jio instance lazily
	 *
	 * @param executor  the Executor running the asynchronous code
	 * @param aSupplier a value supplier
	 * @param <R>       the Environment type
	 * @param <E>       the Failure type
	 * @param <A>       the Value type
	 * @return          a Jio instance
	 */
	public static <R,E,A> Jio<R,E,A> successLazy(Executor executor, Supplier<A> aSupplier) {
		final Promise<R,E,A> p = new Promise<>();

		executor.execute(new Runnable() {
			@Override
			public void run() {
				A a = aSupplier.get();
				p.setDelegate(Jio.success(a));
			}
		});

		return p;
	}

	/**
	 * Represents a Jio instance that runs its effects
	 * every time unsafeRun is called.
	 *
	 * @param <R> the Environment type
	 * @param <E> the Failure type
	 * @param <A> the Value type
	 */
	public static final class EvalAlways<R,E,A> extends Jio<R,E,A> {
		private final Supplier<Jio<R,E,A>> jioSupplier;

		private EvalAlways(Supplier<Jio<R, E, A>> jioSupplier) {
			this.jioSupplier = jioSupplier;
		}

		@Override
		public <E1 extends E, E2 extends E, Z> Jio<R, E, Z> foldM(Function<E, Jio<R, E1, Z>> onFail, Function<A, Jio<R, E2, Z>> onPass) {
			return jioSupplier.get().foldM(onFail,onPass);
		}

		@Override
		public <F> Jio<R, F, A> mapError(Function<E, F> fn) {
			return jioSupplier.get().mapError(fn);
		}

		@Override
		public void unsafeRun(R r, BiConsumer<E, A> blk) {
			jioSupplier.get().unsafeRun(r,blk);
		}
	}

	/**
	 * Represents a Jio instance that cannot succeed and
	 * does not require a value.
	 *
	 * @param <R> the Environment type
	 * @param <E> the Failure type
	 * @param <A> the Value type
	 */
	public static final class Failure<R,E,A> extends Jio<R,E,A> {
		private final E error;

		private Failure(E error) {
			this.error = error;
		}

		@Override
		public <E1 extends E, E2 extends E, Z> Jio<R, E, Z> foldM(Function<E, Jio<R, E1, Z>> onFail, Function<A, Jio<R, E2, Z>> onPass) {
			return onFail.apply(error).mapError(e1 -> (E)e1);
		}

		@Override
		public <F> Jio<R, F, A> mapError(Function<E, F> fn) {
			return new Failure<>(fn.apply(error));
		}

		@Override
		public void unsafeRun(R r, BiConsumer<E, A> blk) {
			blk.accept(error,null);
		}
	}

	/**
	 * Represents a Jio instance that completes asynchronously.
	 *
	 * @param <R> the Environment type
	 * @param <E> the Failure type
	 * @param <A> the Value type
	 */
	public static final class Promise<R,E,A> extends Jio<R,E,A> {
		public static interface Listener<R,E,A> {
			public void onComplete(Jio<R,E,A> jio);
		}

		private final Queue<Listener<R,E,A>> onCompleteListeners = new ArrayDeque<>();

		private Jio<R,E,A> delegate;

		public void setDelegate(Jio<R, E, A> delegate) {
			this.delegate = delegate;
		}

		@Override
		public <E1 extends E, E2 extends E, Z> Jio<R, E, Z> foldM(Function<E, Jio<R, E1, Z>> onFail, Function<A, Jio<R, E2, Z>> onPass) {
			final Promise<R,E,Z> p = new Promise<>();

			if (delegate == null) {
				onCompleteListeners.add(new Listener<R, E, A>() {
					@Override
					public void onComplete(Jio<R, E, A> jio) {
						p.setDelegate(jio.foldM(onFail,onPass));
					}
				});
			} else {
				p.setDelegate(delegate.foldM(onFail,onPass));
			}

			return p;
		}

		@Override
		public <F> Jio<R, F, A> mapError(Function<E, F> fn) {
			final Promise<R,F,A> p = new Promise<>();

			if (delegate == null) {
				onCompleteListeners.add(new Listener<R, E, A>() {
					@Override
					public void onComplete(Jio<R, E, A> jio) {
						p.setDelegate(jio.mapError(fn));
					}
				});
			} else {
				p.setDelegate(delegate.mapError(fn));
			}

			return p;
		}

		@Override
		public void unsafeRun(R r, BiConsumer<E, A> blk) {
			if (delegate == null) {
				onCompleteListeners.add(new Listener<R, E, A>() {
					@Override
					public void onComplete(Jio<R, E, A> jio) {
						jio.unsafeRun(r, blk);
					}
				});
			} else {
				delegate.unsafeRun(r,blk);
			}
		}
	}

	/**
	 * Represents a Jio instance that accepts an environment value,
	 * transforms it, and emits the result using another consumer.
	 *
	 * @param <R> the Environment type
	 * @param <E> the Failure type
	 * @param <A> the Value type
	 */
	public static final class SinkAndSource<R,E,A> extends Jio<R,E,A> {
		private final BiConsumer<R,BiConsumer<E,A>> bic;

		private SinkAndSource(BiConsumer<R, BiConsumer<E, A>> bic) {
			this.bic = bic;
		}

		@Override
		public <E1 extends E, E2 extends E, Z> Jio<R, E, Z> foldM(Function<E, Jio<R, E1, Z>> onFail, Function<A, Jio<R, E2, Z>> onPass) {
			return new SinkAndSource<R,E,Z>(new BiConsumer<R, BiConsumer<E, Z>>() {
				@Override
				public void accept(R r, BiConsumer<E, Z> ezBiConsumer) {
					bic.accept(r, (e,a) -> {
						if (e != null) {
							onFail.apply(e).unsafeRun(r, ezBiConsumer::accept);
						} else {
							onPass.apply(a).unsafeRun(r, ezBiConsumer::accept);
						}
					});
				}
			});
		}

		@Override
		public <F> Jio<R, F, A> mapError(Function<E, F> fn) {
			return new SinkAndSource<R,F,A>(new BiConsumer<R, BiConsumer<F, A>>() {
				@Override
				public void accept(R r, BiConsumer<F, A> faBiConsumer) {
					bic.accept(r, (e,a) -> {
						if (e != null) {
							faBiConsumer.accept(fn.apply(e), null);
						} else {
							faBiConsumer.accept(null, a);
						}
					});
				}
			});
		}

		@Override
		public void unsafeRun(R r, BiConsumer<E, A> blk) {
			bic.accept(r,blk);
		}
	}

	/**
	 * Represents a Jio instance that cannot fail and does
	 * not require a value.
	 *
	 * @param <R> the Environment type
	 * @param <E> the Failure type
	 * @param <A> the Value type
	 */
	public static final class Success<R,E,A> extends Jio<R,E,A> {
		private final A value;

		private Success(A value) {
			this.value = value;
		}

		@Override
		public <E1 extends E, E2 extends E, Z> Jio<R, E, Z> foldM(Function<E, Jio<R, E1, Z>> onFail, Function<A, Jio<R, E2, Z>> onPass) {
			return onPass.apply(value).mapError(e2 -> (E)e2);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <F> Jio<R, F, A> mapError(Function<E, F> fn) {
			return (Jio<R,F,A>)this;
		}

		@Override
		public void unsafeRun(R r, BiConsumer<E, A> blk) {
			blk.accept(null,value);
		}
	}
}
