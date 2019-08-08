package com.crossman;

import com.crossman.util.CheckedFunction;
import com.crossman.util.CheckedSupplier;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

public abstract class Jio<R,E,A> {

	// sealed type
	private Jio() {}

	public final <EE extends E,B> Jio<R,E,B> bracket(Function<A,Jio<R,Void,Void>> onFinally, Function<A,Jio<R,EE,B>> onTry) {
		return this.flatMap(a -> onTry.apply(a).ensuring(onFinally.apply(a)));
	}

	public final <F, AA extends A> Jio<R,F,A> catchAll(Function<Cause<E>,Jio<R,F,AA>> fn) {
		return this.<F,A>foldCauseM(c -> fn.apply(c).map(aa -> (A)aa), Jio::success);
	}

	public abstract Jio<R,E,A> ensuring(Jio<R,Void,Void> finalizer);

	public final <EE extends E, B> Jio<R,E,B> flatMap(Function<A,Jio<R,EE,B>> fn) {
		return this.<E,B>foldCauseM(Jio::failCause, a -> fn.apply(a).mapError(ee -> (E)ee));
	}

	public final <Z> Jio<R,Void,Z> fold(Function<E,Z> onFail, Function<A,Z> onPass) {
		return this.foldM(e -> Jio.success(onFail.apply(e)), a -> Jio.success(onPass.apply(a)));
	}

	public final <Z> Jio<R,Void,Z> foldCause(Function<Cause<E>,Z> onFail, Function<A,Z> onPass) {
		return this.foldCauseM(c -> Jio.success(onFail.apply(c)), a -> Jio.success(onPass.apply(a)));
	}

	public final <F,Z> Jio<R,F,Z> foldM(Function<E,Jio<R,F,Z>> onFail, Function<A,Jio<R,F,Z>> onPass) {
		return this.foldCauseM(eCause -> {
			if (eCause.getError() != null) {
				return onFail.apply(eCause.getError());
			}
			return Jio.<R,F,Z>failCause(new Cause<F>(null, eCause.getCause()));
		}, onPass);
	}

	public abstract <F, Z> Jio<R,F,Z> foldCauseM(Function<Cause<E>,Jio<R,F,Z>> onFail, Function<A,Jio<R,F,Z>> onPass);

	public final <B> Jio<R,E,B> map(Function<A,B> fn) {
		return flatMap(a -> Jio.success(fn.apply(a)));
	}

	public final <F> Jio<R,F,A> mapError(Function<E,F> fn) {
		return catchAll(c -> Jio.failCause(c.map(fn)));
	}

	public final <EE extends E, AA extends A> Jio<R,E,A> race(Jio<R,EE,AA> that) {
		return race(that, Executors.newCachedThreadPool());
	}

	public final <EE extends E, AA extends A> Jio<R,E,A> race(Jio<R,EE,AA> that, Executor executor) {
		final AtomicBoolean resolved = new AtomicBoolean(false);

		final Jio<R, E, A> self = this;
		return new SinkAndSource<>(new BiConsumer<R, BiConsumer<Cause<E>, A>>() {
			@Override
			public void accept(R r, BiConsumer<Cause<E>, A> eaBiConsumer) {
				executor.execute(() -> {
					self.unsafeRun(r, (e,a) -> {
						if (!resolved.get()) {
							if (e == null) {
								resolved.set(true);
								eaBiConsumer.accept(null, a);
							}
						}
					});
				});
				executor.execute(() -> {
					that.unsafeRun(r, (e,aa) -> {
						if (!resolved.get()) {
							if (e == null) {
								resolved.set(true);
								eaBiConsumer.accept(null, aa);
							}
						}
					});
				});
			}
		});
	}

	public abstract void unsafeRun(R environment, BiConsumer<Cause<E>,A> blk);

	public final <EE extends E, B, Z> Jio<R, E, Z> zip(Jio<R,EE,B> that, BiFunction<A,B,Z> fn) {
		return flatMap(a -> that.map(b -> fn.apply(a,b)));
	}

	public final <EE extends E, B> Jio<R, E, A> zipLeft(Jio<R,EE,B> that) {
		return zip(that, (a,b) -> a);
	}

	public final <EE extends E, B> Jio<R, E, B> zipRight(Jio<R,EE,B> that) {
		return zip(that, (a,b) -> b);
	}

	public final <EE extends E, B, Z> Jio<R, E, Z> zipPar(Jio<R,EE,B> that, BiFunction<A,B,Z> fn) {
		return zipPar(that, fn, Executors.newCachedThreadPool());
	}

	public final <EE extends E, B, Z> Jio<R, E, Z> zipPar(Jio<R,EE,B> that, BiFunction<A,B,Z> fn, Executor executor) {
		final Jio<R, E, A> self = this;
		final AtomicReference<Optional<A>> maybeARef = new AtomicReference<>(Optional.empty());
		final AtomicReference<Optional<B>> maybeBRef = new AtomicReference<>(Optional.empty());
		final AtomicBoolean resolved = new AtomicBoolean(false);

		return new SinkAndSource<>(new BiConsumer<R, BiConsumer<Cause<E>, Z>>() {
			@Override
			public void accept(R r, BiConsumer<Cause<E>, Z> causeZBiConsumer) {
				executor.execute(() -> {
					self.unsafeRun(r, ((eCause, a) -> {
						if (!resolved.get()) {
							if (eCause != null) {
								resolved.set(true);
								// that.interrupt()
								causeZBiConsumer.accept(eCause,null);
							} else {
								final Optional<B> maybeB = maybeBRef.get();
								if (maybeB.isPresent()) {
									final B b = maybeB.get();
									resolved.set(true);
									Z z = fn.apply(a, b);
									causeZBiConsumer.accept(null, z);
								} else {
									maybeARef.set(Optional.ofNullable(a));
								}
							}
						}
					}));
				});
				executor.execute(() -> {
					that.unsafeRun(r, ((eeCause, b) -> {
						if (!resolved.get()) {
							if (eeCause != null) {
								resolved.set(true);
								// self.interrupt()
								causeZBiConsumer.accept(eeCause.map(ee -> (E)ee), null);
							} else {
								final Optional<A> maybeA = maybeARef.get();
								if (maybeA.isPresent()) {
									final A a = maybeA.get();
									resolved.set(true);
									Z z = fn.apply(a,b);
									causeZBiConsumer.accept(null, z);
								} else {
									maybeBRef.set(Optional.ofNullable(b));
								}
							}
						}
					}));
				});
			}
		});
	}

	/***********************************************/

	/**
	 * constructs a Jio by building a list of values, or failing with an error
	 *
	 * @param jios      the collection of Jio instances
	 * @param <R>       the Environment type
	 * @param <E>       the Failure type
	 * @param <A>       the Value type
	 * @return          a Jio instance
	 */
	public static <R,E,A> Jio<R,E,List<A>> collect(Collection<Jio<R,E,A>> jios) {
		return reduce(Collections.emptyList(), jios, (lst,a) -> {
			final List<A> ret = new ArrayList<>(lst);
			ret.add(a);
			return ret;
		});
	}

	/**
	 * constructs a Jio by building a list of values, or failing with an error
	 *
	 * @param jios      the collection of Jio instances
	 * @param <R>       the Environment type
	 * @param <E>       the Failure type
	 * @param <A>       the Value type
	 * @return          a Jio instance
	 */
	public static <R,E,A> Jio<R,E,List<A>> collectPar(Collection<Jio<R,E,A>> jios) {
		return reducePar(Collections.emptyList(), jios, (lst,a) -> {
			final List<A> ret = new ArrayList<>(lst);
			ret.add(a);
			return ret;
		});
	}

	@SuppressWarnings("unchecked")
	public static <R,T extends Throwable,A> Jio<R,T,A> effect(CheckedSupplier<T,A> taCheckedSupplier) {
		return new EvalAlways<>(() -> {
			try {
				A a = taCheckedSupplier.get();
				return Jio.success(a);
			} catch (Throwable th) {
				T t = (T)th;
				return Jio.fail(t);
			}
		});
	}

	public static <R,E,A> Jio<R,E,A> effectTotal(Supplier<A> aSupplier) {
		return new EvalAlways<>(() -> Jio.successLazy(aSupplier));
	}

	public static <R,E,A> Jio<R,E,A> effectAsync(Consumer<Consumer<Jio<R,E,A>>> blk) {
		Promise<R,E,A> p = new Promise<>();
		blk.accept(p::setDelegate);
		return p;
	}

	public static <R,E,A> Jio<R,E,A> fail(E error) {
		return new Failure<>(new Cause<>(error));
	}

	public static <R,E,A> Jio<R,E,A> failCause(Cause<E> cause) {
		return new Failure<>(cause);
	}

	/**
	 * effectfully loop over values
	 *
	 * @param jios  the sequence of Jio instances
	 * @param blk   the code block
	 * @param <R>   the Environment type
	 * @param <E>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	public static <R,E,A> Jio<R,E,Void> foreach(Collection<Jio<R,E,A>> jios, Consumer<A> blk) {
		return reduce(null, jios, ($,a) -> {
			blk.accept(a);
			return null;
		});
	}

	/**
	 * effectfully loop over values
	 *
	 * @param jios  the sequence of Jio instances
	 * @param blk   the code block
	 * @param <R>   the Environment type
	 * @param <E>   the Failure type
	 * @param <A>   the Value type
	 * @return      a Jio instance
	 */
	public static <R,E,A> Jio<R,E,Void> foreachPar(Collection<Jio<R,E,A>> jios, Consumer<A> blk) {
		return reducePar(null, jios, ($,a) -> {
			blk.accept(a);
			return null;
		});
	}

	@SuppressWarnings("unchecked")
	public static <R,T extends Throwable,A> Jio<R,T,A> fromFunction(CheckedFunction<T,R,A> fn) {
		return new SinkAndSource<>(new BiConsumer<R, BiConsumer<Cause<T>, A>>() {
			@Override
			public void accept(R r, BiConsumer<Cause<T>, A> causeABiConsumer) {
				try {
					A a = fn.apply(r);
					causeABiConsumer.accept(null, a);
				} catch (Throwable th) {
					T t = (T)th;
					causeABiConsumer.accept(new Cause<>(t), null);
				}
			}
		});
	}

	public static <R,A> Jio<R, CompletionException, A> fromFuture(CompletionStage<A> completionStage) {
		final Promise<R,CompletionException,A> p = new Promise<>();

		completionStage.whenCompleteAsync((a,th) -> {
			if (th != null) {
				if (th instanceof CompletionException) {
					p.setDelegate(Jio.fail((CompletionException)th));
				} else {
					p.setDelegate(Jio.fail(new CompletionException(th)));
				}
			} else {
				p.setDelegate(Jio.success(a));
			}
		});

		return p;
	}

	public static <R,A> Jio<R, CompletionException, A> fromFuture(CompletionStage<A> completionStage, Executor executor) {
		final Promise<R,CompletionException,A> p = new Promise<>();

		completionStage.whenCompleteAsync((a,th) -> {
			if (th != null) {
				if (th instanceof CompletionException) {
					p.setDelegate(Jio.fail((CompletionException)th));
				} else {
					p.setDelegate(Jio.fail(new CompletionException(th)));
				}
			} else {
				p.setDelegate(Jio.success(a));
			}
		}, executor);

		return p;
	}

	@SuppressWarnings("unchecked")
	public static <R,A> Jio<R,Void,A> fromTotalFunction(Function<R,A> fn) {
		return new SinkAndSource<>(new BiConsumer<R, BiConsumer<Cause<Void>, A>>() {
			@Override
			public void accept(R r, BiConsumer<Cause<Void>, A> causeABiConsumer) {
				A a = fn.apply(r);
				causeABiConsumer.accept(null, a);
			}
		});
	}

	public static <R,T extends Throwable,A> Jio<R,T,A> fromTrying(CheckedSupplier<T,A> taCheckedSupplier) {
		try {
			A a = taCheckedSupplier.get();
			return success(a);
		} catch (Throwable th) {
			T t = (T)th;
			return fail(t);
		}
	}

	public static <R,E,A> Promise<R,E,A> promise() {
		return new Promise<>();
	}

	public static <R,E,A,Z> Jio<R,E,Z> reduce(Z zero, Collection<Jio<R,E,A>> jios, BiFunction<Z,A,Z> reducer) {
		final AtomicReference<Jio<R,E,Z>> sumRef = new AtomicReference<>(Jio.success(zero));

		jios.forEach(jio -> {
			final Jio<R,E,Z> sum = sumRef.get();
			final Jio<R,E,Z> newSum = sum.zip(jio, reducer);
			sumRef.set(newSum);
		});

		return sumRef.get();
	}

	public static <R,E,A,Z> Jio<R,E,Z> reducePar(Z zero, Collection<Jio<R,E,A>> jios, BiFunction<Z,A,Z> reducer) {
		final AtomicReference<Jio<R,E,Z>> sumRef = new AtomicReference<>(Jio.success(zero));

		jios.forEach(jio -> {
			final Jio<R,E,Z> sum = sumRef.get();
			final Jio<R,E,Z> newSum = sum.zipPar(jio, reducer);
			sumRef.set(newSum);
		});

		return sumRef.get();
	}

	public static <R,E,A> Jio<R,E,A> success(A value) {
		return new Success<>(value);
	}

	public static <R,E,A> Jio<R,E,A> successLazy(Supplier<A> aSupplier) {
		return successLazy(aSupplier, Executors.newSingleThreadExecutor());
	}

	public static <R,E,A> Jio<R,E,A> successLazy(Supplier<A> aSupplier, Executor executor) {
		final Promise<R,E,A> p = new Promise<>();

		executor.execute(() -> {
			A a = aSupplier.get();
			p.setDelegate(Jio.success(a));
		});

		return p;
	}

	/***********************************************/

	public static final class Failure<R,E,A> extends Jio<R,E,A> {
		private final Cause<E> cause;

		private Failure(Cause<E> cause) {
			this.cause = cause;
		}

		@Override
		public Jio<R, E, A> ensuring(Jio<R, Void, Void> finalizer) {
			return finalizer.foldCauseM($ -> this, $ -> this);
		}

		@Override
		public <F, Z> Jio<R, F, Z> foldCauseM(Function<Cause<E>, Jio<R, F, Z>> onFail, Function<A, Jio<R, F, Z>> onPass) {
			return onFail.apply(cause);
		}

		@Override
		public void unsafeRun(R environment, BiConsumer<Cause<E>, A> blk) {
			blk.accept(cause,null);
		}
	}

	public static final class Success<R,E,A> extends Jio<R,E,A> {
		private final A value;

		private Success(A value) {
			this.value = value;
		}

		@Override
		public Jio<R, E, A> ensuring(Jio<R, Void, Void> finalizer) {
			return finalizer.foldCauseM($ -> this, $ -> this);
		}

		@Override
		public <F, Z> Jio<R, F, Z> foldCauseM(Function<Cause<E>, Jio<R, F, Z>> onFail, Function<A, Jio<R, F, Z>> onPass) {
			return onPass.apply(value);
		}

		@Override
		public void unsafeRun(R environment, BiConsumer<Cause<E>, A> blk) {
			blk.accept(null, value);
		}
	}

	public static final class EvalAlways<R,E,A> extends Jio<R,E,A> {
		private final Supplier<Jio<R,E,A>> jioSupplier;

		private EvalAlways(Supplier<Jio<R, E, A>> jioSupplier) {
			this.jioSupplier = jioSupplier;
		}

		@Override
		public Jio<R, E, A> ensuring(Jio<R, Void, Void> finalizer) {
			return new EvalAlways<>(() -> jioSupplier.get().ensuring(finalizer));
		}

		@Override
		public <F, Z> Jio<R, F, Z> foldCauseM(Function<Cause<E>, Jio<R, F, Z>> onFail, Function<A, Jio<R, F, Z>> onPass) {
			return new EvalAlways<>(() -> jioSupplier.get().foldCauseM(onFail, onPass));
		}

		@Override
		public void unsafeRun(R environment, BiConsumer<Cause<E>, A> blk) {
			jioSupplier.get().unsafeRun(environment, blk);
		}
	}

	public static final class Promise<R,E,A> extends Jio<R,E,A> {
		public static interface OnDoneListener<R,E,A> {
			public void onDone(Jio<R,E,A> jio);
		}

		private final Queue<OnDoneListener<R,E,A>> onDoneListeners = new ArrayDeque<>();

		private Jio<R,E,A> delegate;

		private Promise() {}

		@Override
		public Jio<R, E, A> ensuring(Jio<R, Void, Void> finalizer) {
			final Promise<R,E,A> p = new Promise<>();

			if (delegate == null) {
				onDoneListeners.add(new OnDoneListener<R, E, A>() {
					@Override
					public void onDone(Jio<R, E, A> jio) {
						p.setDelegate(jio.ensuring(finalizer));
					}
				});
			} else {
				p.setDelegate(delegate.ensuring(finalizer));
			}

			return p;
		}

		@Override
		public <F, Z> Jio<R, F, Z> foldCauseM(Function<Cause<E>, Jio<R, F, Z>> onFail, Function<A, Jio<R, F, Z>> onPass) {
			final Promise<R,F,Z> p = new Promise<>();

			if (delegate == null) {
				onDoneListeners.add(new OnDoneListener<R, E, A>() {
					@Override
					public void onDone(Jio<R, E, A> jio) {
						p.setDelegate(jio.foldCauseM(onFail, onPass));
					}
				});
			} else {
				p.setDelegate(delegate.foldCauseM(onFail, onPass));
			}

			return p;
		}

		@Override
		public void unsafeRun(R environment, BiConsumer<Cause<E>, A> blk) {
			if (delegate == null) {
				onDoneListeners.add(new OnDoneListener<R, E, A>() {
					@Override
					public void onDone(Jio<R, E, A> jio) {
						jio.unsafeRun(environment, blk);
					}
				});
			} else {
				delegate.unsafeRun(environment, blk);
			}
		}

		public void setDelegate(Jio<R, E, A> delegate) {
			this.delegate = delegate;
		}
	}

	public static final class SinkAndSource<R,E,A> extends Jio<R,E,A> {
		private final BiConsumer<R,BiConsumer<Cause<E>,A>> bic;

		private SinkAndSource(BiConsumer<R, BiConsumer<Cause<E>, A>> bic) {
			this.bic = bic;
		}

		@Override
		public Jio<R, E, A> ensuring(Jio<R, Void, Void> finalizer) {
			return new SinkAndSource<>(new BiConsumer<R, BiConsumer<Cause<E>, A>>() {
				@Override
				public void accept(R r, BiConsumer<Cause<E>, A> causeABiConsumer) {
					bic.accept(r, ((eCause, a) -> {
						finalizer.unsafeRun(r, ($1,$2) -> {
							causeABiConsumer.accept(eCause,a);
						});
					}));
				}
			});
		}

		@Override
		public <F, Z> Jio<R, F, Z> foldCauseM(Function<Cause<E>, Jio<R, F, Z>> onFail, Function<A, Jio<R, F, Z>> onPass) {
			return new SinkAndSource<>(new BiConsumer<R, BiConsumer<Cause<F>, Z>>() {
				@Override
				public void accept(R r, BiConsumer<Cause<F>, Z> causeZBiConsumer) {
					bic.accept(r, ((eCause, a) -> {
						if (eCause != null) {
							Jio<R,F,Z> jio = onFail.apply(eCause);
							jio.unsafeRun(r, causeZBiConsumer);
						} else {
							Jio<R,F,Z> jio = onPass.apply(a);
							jio.unsafeRun(r, causeZBiConsumer);
						}
					}));
				}
			});
		}

		@Override
		public void unsafeRun(R environment, BiConsumer<Cause<E>, A> blk) {
			bic.accept(environment,blk);
		}
	}
}
