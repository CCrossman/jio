package com.crossman;

import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static junit.framework.TestCase.*;

public class JioTest {

	private static <R,E,A> void assertCalled(Jio<R,E,A> jio, R environment, BiConsumer<Cause<E>,A> blk) {
		assertCalled(100, jio, environment, blk);
	}

	private static <R,E,A> void assertCalled(long delay, Jio<R,E,A> jio, R environment, BiConsumer<Cause<E>,A> blk) {
		AtomicBoolean called = new AtomicBoolean(false);
		jio.unsafeRun(environment, ((eCause, a) -> {
			blk.accept(eCause,a);
			called.set(true);
		}));
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		assertTrue("unsafeRun block was not called within the delay",called.get());
	}

	private static <R,E,A> void assertNotCalled(Jio<R,E,A> jio, R environment, BiConsumer<Cause<E>,A> blk) {
		assertNotCalled(100, jio, environment, blk);
	}

	private static <R,E,A> void assertNotCalled(long delay, Jio<R,E,A> jio, R environment, BiConsumer<Cause<E>,A> blk) {
		AtomicBoolean called = new AtomicBoolean(false);
		jio.unsafeRun(environment, ((eCause, a) -> {
			blk.accept(eCause,a);
			called.set(true);
		}));
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		assertFalse("unsafeRun block was called within the delay",called.get());
	}

	@Test
	public void testJioSuccess() {
		Jio<Void,Void,String> jio = Jio.success("Hello");
		assertCalled(jio, null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Hello", a);
		});
	}

	@Test
	public void testJioFailure() {
		Jio<Void,String,Integer> jio = Jio.fail("Bad request");
		assertCalled(jio, null, (ex,a) -> {
			assertEquals(new Cause<>("Bad request"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioSuccessLazyDoesNotBlock() {
		Jio<Void,Void,Long> jio = Jio.successLazy(new Supplier<Long>() {
			@Override
			public Long get() {
				long l = 0L;
				for (long i = 0L; i < 1000000; i = i + 1) {
					l = l + i;
				}
				return l;
			}
		});
		assertNotNull(jio);
	}

	@Test
	public void testJioSuccessLazyOnlyRunOnceWhenNecessary() {
		final List<String> out = new ArrayList<>();
		Jio<Void,Void,String> jio = Jio.successLazy(() -> {
			out.add("Bang!");
			return "BLAM!";
		});

		assertEquals(0, out.size());

		assertCalled(jio,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("BLAM!", a);
		});
		assertEquals(1, out.size());

		assertCalled(jio,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("BLAM!", a);
		});
		assertEquals(1, out.size());
	}

	@Test
	public void testJioEffectRunsEveryTime() {
		final List<String> out = new ArrayList<>();
		Jio<Void,Void,String> jio = Jio.effectTotal(() -> {
			out.add("Hello");
			return "World";
		});

		assertEquals(0, out.size());

		assertCalled(jio,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("World", a);
		});
		assertEquals(1, out.size());

		assertCalled(jio,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("World", a);
		});
		assertEquals(2, out.size());
	}

	@Test
	public void testJioFromTrying() {
		Jio<Void,ArithmeticException,Integer> jio = Jio.fromTrying(() -> 42 / 0);
		assertNotNull(jio);

		assertCalled(jio,null, (ex,a) -> {
			assertNotNull(ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFromFunction() {
		Jio<Integer,Throwable,Integer> jio = Jio.fromFunction(i -> i * (i+1));
		assertCalled(jio,5, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(30), a);
		});
	}

	@Test
	public void testJioFromFuture() {
		final CompletableFuture<String> future = new CompletableFuture<>();
		Jio<Void, CompletionException, String> jio = Jio.fromFuture(future);
		future.complete("Hello World!");
		assertCalled(jio,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Hello World!", a);
		});
	}

	private static void loginOK(Consumer<String> onSuccess, Consumer<String> onFailure) {
		onSuccess.accept("Logged in");
	}

	private static void loginFail(Consumer<String> onSuccess, Consumer<String> onFailure) {
		onFailure.accept("Authentication failed");
	}

	@Test
	public void testJioFromEffectAsyncOK() {
		Jio<Void,String,String> jio = Jio.effectAsync(cbk -> {
			loginOK(str1 -> cbk.accept(Jio.success(str1)), str2 -> cbk.accept(Jio.fail(str2)));
		});
		assertNotNull(jio);
		assertCalled(jio,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Logged in", a);
		});
	}

	@Test
	public void testJioFromEffectAsyncFail() {
		Jio<Void,String,String> jio = Jio.effectAsync(cbk -> {
			loginFail(str1 -> cbk.accept(Jio.success(str1)), str2 -> cbk.accept(Jio.fail(str2)));
		});
		assertNotNull(jio);
		assertCalled(jio,null, (ex,a) -> {
			assertEquals(new Cause<>("Authentication failed"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMappableFromSuccess() {
		Jio<Void,String,Integer> jio1 = Jio.success(10);
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("X10", a);
		});
	}

	@Test
	public void testJioMappableFromFailure() {
		Jio<Void,String,Integer> jio1 = Jio.fail("Bad request");
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		assertCalled(jio2,null, (ex,a) -> {
			assertEquals(new Cause<>("Bad request"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMappableFromEvalAlways() {
		Jio<Void,String,Integer> jio1 = Jio.effectTotal(() -> 5);
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("X5", a);
		});
	}

	@Test
	public void testJioMappableFromPromise() {
		Jio.Promise<Void,String,Integer> jio1 = Jio.promise();
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		jio1.setDelegate(Jio.success(4));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("X4", a);
		});
	}

	@Test
	public void testJioMappableFromSinkAndSource() {
		Jio<Integer,Throwable,Integer> jio1 = Jio.fromFunction(i -> i + 1);
		Jio<Integer,Throwable,String> jio2 = jio1.map(i -> "X" + i);
		assertCalled(jio2,3, (ex,a) -> {
			assertNull(ex);
			assertEquals("X4", a);
		});
	}

	@Test
	public void testJioMapErrorFromSuccess() {
		Jio<Void,String,Integer> jio1 = Jio.success(10);
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(10),a);
		});
	}

	@Test
	public void testJioMapErrorFromFailure() {
		Jio<Void,String,Integer> jio1 = Jio.fail("Bad request");
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		assertCalled(jio2,null, (ex,a) -> {
			assertEquals(new Cause<>(11), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMapErrorFromEvalAlways() {
		Jio<Void,String,Integer> jio1 = Jio.effectTotal(() -> 5);
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(5), a);
		});
	}

	@Test
	public void testJioMapErrorFromPromise() {
		Jio.Promise<Void,String,Integer> jio1 = Jio.promise();
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		jio1.setDelegate(Jio.fail("hiya"));
		assertCalled(jio2,null, (ex,a) -> {
			assertEquals(new Cause<>(4),ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMapErrorFromSinkAndSource() {
		Jio<Integer,Throwable,Integer> jio1 = Jio.fromFunction(i -> i + 1);
		Jio<Integer,IllegalArgumentException,Integer> jio2 = jio1.mapError(IllegalArgumentException::new);
		assertCalled(jio2,3, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(4), a);
		});
	}

	@Test
	public void testJioFlatMapFromSuccess() {
		Jio<Void,String,String> jio1 = Jio.success("Hello World");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("HELLO WORLD", a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		assertCalled(jio3,null, (ex,a) -> {
			assertEquals(new Cause<>("hello world"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFlatMapFromFailure() {
		Jio<Void,String,String> jio1 = Jio.fail("Bad Request");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		assertCalled(jio2,null, (ex,a) -> {
			assertEquals(new Cause<>("Bad Request"), ex);
			assertNull(a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		assertCalled(jio3,null, (ex,a) -> {
			assertEquals(new Cause<>("Bad Request"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFlatMapFromPromise() {
		Jio.Promise<Void,String,String> jio1 = Jio.promise();
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));

		jio1.setDelegate(Jio.success("FooBar"));

		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("FOOBAR", a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.success(s.toLowerCase()));
		assertCalled(jio3,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("foobar", a);
		});
	}

	@Test
	public void testJioFlatMapFromEvalAlways() {
		Jio<Void,String,String> jio1 = Jio.effectTotal(() -> "Foo");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("FOO", a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		assertCalled(jio3,null, (ex,a) -> {
			assertEquals(new Cause<>("foo"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFlatMapFromSinkAndSource() {
		Jio<Integer, String, String> jio1 = Jio.<Integer,Throwable,String>fromFunction(i -> "Xx" + i).mapError(t -> t.toString());
		Jio<Integer, String, String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		assertCalled(jio2,5, (ex,a) -> {
			assertNull(ex);
			assertEquals("XX5", a);
		});

		Jio<Integer, String, String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		assertCalled(jio3,4, (ex,a) -> {
			assertEquals(new Cause<>("xx4"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioCatchAllFromSuccess() {
		Jio<Void, String, Integer> jio1 = Jio.success(5);
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		assertCalled(jio2, null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(5), a);
		});
	}

	@Test
	public void testJioCatchAllFromFailure() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Bad Request");
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(11), a);
		});
	}

	@Test
	public void testJioCatchAllFromPromise() {
		Jio.Promise<Void, String, Integer> jio1 = Jio.promise();
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		jio1.setDelegate(Jio.fail("Hello"));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(5), a);
		});
	}

	@Test
	public void testJioCatchAllFromEvalAlways() {
		AtomicInteger ai = new AtomicInteger(0);
		Jio<Void, String, Integer> jio1 = Jio.effectTotal(ai::incrementAndGet);
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(1), a);
		});
	}

	@Test
	public void testJioCatchAllFromSinkAndSource() {
		Jio<String, Throwable, Integer> jio1 = Jio.fromFunction(s -> {
			if (s.isEmpty()) {
				throw new UnsupportedOperationException();
			}
			return s.length();
		});
		Jio<String, Void, Integer> jio2 = jio1.catchAll(t -> Jio.success(-1));
		assertCalled(jio2,"", ($,i) -> {
			assertNull($);
			assertEquals(Integer.valueOf(-1),i);
		});
		assertCalled(jio2,"Foobar", ($,i) -> {
			assertNull($);
			assertEquals(Integer.valueOf(6), i);
		});
	}

	@Test
	public void testEnsuringOverEffect() throws InterruptedException {
		final List<String> out = new ArrayList<>();
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.effectTotal(() -> {
			out.add("Finalized!");
			return null;
		}));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
		Thread.sleep(100);
		assertEquals(1, out.size());
	}

	@Test
	public void testEnsuringOverSinkAndSource() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.fromTotalFunction($ -> null));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testEnsuringOverPromise() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.promise());
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testEnsuringOverSuccess() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.success(null));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testEnsuringOverFailure() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.fail(null));
		assertCalled(jio2,null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testEnsuringOverTwoEffects() throws InterruptedException {
		final List<String> out = Collections.synchronizedList(new ArrayList<>());
		Jio<Void, String, Integer> jio1 = Jio.effectTotal(() -> {
			out.add("Hello");
			return 1;
		});
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.effectTotal(() -> {
			out.add("World");
			return null;
		}));
		assertCalled(jio2,null, (ex,a) -> {
			assertEquals(Collections.singletonList("Hello"),out);
			assertNull(ex);
			assertEquals(Integer.valueOf(1), a);
		});
		Thread.sleep(100);
		assertEquals(Arrays.asList("Hello", "World"), out);
	}

	@Test
	public void testBracket() {
		Jio<String, FileNotFoundException, File> jio1 = Jio.fromFunction(File::new);
		Jio<String, FileNotFoundException, Long> jio2 = jio1.bracket(f -> Jio.effectTotal(() -> null), f -> Jio.effectTotal(() -> f.length()));
		assertCalled(jio2,"/not/a/file", (ex,a) -> {
			assertNull(ex);
			assertEquals(Long.valueOf(0), a);
		});
	}

	@Test
	public void testZipPar() {
		Jio<Void, InterruptedException, String> jio1 = Jio.effect(() -> {
			Thread.sleep(500L);
			return "Hello";
		});

		Jio<Void, InterruptedException, String> jio2 = Jio.effect(() -> {
			Thread.sleep(600L);
			return "World";
		});

		assertCalled(700, jio1.zipPar(jio2, (a,b) -> a + " " + b),null, (ie,s) -> {
			assertNull(ie);
			assertEquals("Hello World", s);
		});
	}

	@Test
	public void testCollectPar() {
		Jio<Void, InterruptedException, String> jio1 = Jio.effect(() -> {
			Thread.sleep(500);
			return "Hel";
		});
		Jio<Void, InterruptedException, String> jio2 = Jio.effect(() -> {
			Thread.sleep(500);
			return "lo W";
		});
		Jio<Void, InterruptedException, String> jio3 = Jio.effect(() -> {
			Thread.sleep(600);
			return "orld";
		});

		assertCalled(700, Jio.collectPar(Arrays.asList(jio1,jio2,jio3)),null, (ex, lst) -> {
			assertNull(ex);
			assertEquals(lst, Arrays.asList("Hel", "lo W", "orld"));
		});
	}

	@Test
	public void testRaceOk() {
		final Jio<Void, InterruptedException, String> winner = Jio.<Void,InterruptedException,String>effect(() -> {
			Thread.sleep(100);
			return "Hello";
		}).race(Jio.effect(() -> {
			Thread.sleep(200);
			return "World";
		}));
		assertCalled(250, winner,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Hello", a);
		});
	}

	@Test
	public void testRaceFail() {
		final Jio<Void, String, String> winner = Jio.<Void,String,String>fail("Hello").race(Jio.success("Goodbye"));
		assertCalled(winner,null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Goodbye", a);
		});
	}

	@Test
	public void testRaceBothFail() {
		final Jio<Void, String, Integer> winner = Jio.<Void,String,Integer>fail("Hello").race(Jio.fail("Goodbye"));

		assertNotCalled(winner, null, (ex,a) -> {
			assertNotNull(ex);
			assertNull(a);
		});
	}

	@Test
	public void testTimeoutOK() {
		final Jio.Promise<Void, String, Integer> jio = Jio.promise();
		jio.setDelegate(Jio.success(5));

		assertCalled(100, jio.timeout(200, TimeUnit.MILLISECONDS),null, (s, i) -> {
			assertNull(s);
			assertEquals(Optional.of(5), i);
		});
	}

	@Test
	public void testTimeoutFail() {
		final Jio<Void, String, Integer> jio = Jio.promise();

		assertCalled(200, jio.timeoutWith(Cause.interrupted(), 100, TimeUnit.MILLISECONDS),null, (s, i) -> {
			assertNull(s);
			assertEquals(Optional.empty(), i);
		});
	}

	@Test
	public void testInterruptSuccess() {
		final Jio<Void, String, Integer> jio = Jio.success(5);
		jio.interrupt();

		assertCalled(jio, null, (cause, i) -> {
			assertNotNull(cause);
			assertNull(cause.getError());
			assertTrue(cause.getCause() instanceof InterruptedException);
			assertNull(i);
		});
	}

	@Test
	public void testInterruptFail() {
		final Jio<Void, String, Integer> jio = Jio.fail("bad request");
		jio.interrupt();

		assertCalled(jio, null, (cause, i) -> {
			assertNotNull(cause);
			assertNull(cause.getError());
			assertTrue(cause.getCause() instanceof InterruptedException);
			assertNull(i);
		});
	}

	@Test
	public void testInterruptEvalAlways() {
		final Jio<Void, String, Integer> jio = Jio.effectTotal(() -> 7);
		jio.interrupt();

		assertCalled(jio, null, (cause, i) -> {
			assertNotNull(cause);
			assertNull(cause.getError());
			assertTrue(cause.getCause() instanceof InterruptedException);
			assertNull(i);
		});
	}

	@Test
	public void testInterruptPromise() {
		final Jio.Promise<Void, String, Integer> jio1 = Jio.promise();
		final Jio<Void, String, String> jio2 = jio1.map(i -> i.toString());
		jio2.interrupt();
		jio1.setDelegate(Jio.success(4));

		assertCalled(jio2, null, (c,i) -> {
			assertNotNull(c);
			assertNull(c.getError());
			assertTrue(c.getCause() instanceof InterruptedException);
			assertNull(i);
		});
	}

	@Test
	public void testInterruptSinkAndSource() {
		final Jio<Integer, Throwable, String> jio = Jio.fromFunction(Object::toString);
		jio.interrupt();

		assertCalled(jio, 3, (c,i) -> {
			assertNotNull(c);
			assertNull(c.getError());
			assertTrue(c.getCause() instanceof InterruptedException);
			assertNull(i);
		});
	}

	@Test
	public void testInterruptWithSuccess() {
		final Jio<Void, String, Integer> jio = Jio.success(5);
		jio.interrupt("forget it");

		assertCalled(jio, null, (cause, i) -> {
			assertNotNull(cause);
			assertEquals("forget it", cause.getError());
			assertNull(cause.getCause());
			assertNull(i);
		});
	}

	@Test
	public void testInterruptWithFail() {
		final Jio<Void, String, Integer> jio = Jio.fail("bad request");
		jio.interrupt("nevermind");

		assertCalled(jio, null, (cause, i) -> {
			assertNotNull(cause);
			assertEquals("nevermind", cause.getError());
			assertNull(cause.getCause());
			assertNull(i);
		});
	}

	@Test
	public void testInterruptWithEvalAlways() {
		final Jio<Void, String, Integer> jio = Jio.effectTotal(() -> 7);
		jio.interrupt("nada");

		assertCalled(jio, null, (cause, i) -> {
			assertNotNull(cause);
			assertEquals("nada", cause.getError());
			assertNull(cause.getCause());
			assertNull(i);
		});
	}

	@Test
	public void testInterruptWithPromise() {
		final Jio.Promise<Void, String, Integer> jio1 = Jio.promise();
		final Jio<Void, String, String> jio2 = jio1.map(i -> i.toString());
		jio2.interrupt("interrupted");
		jio1.setDelegate(Jio.success(4));

		assertCalled(jio2, null, (c,i) -> {
			assertNotNull(c);
			assertEquals("interrupted", c.getError());
			assertNull(c.getCause());
			assertNull(i);
		});
	}

	@Test
	public void testInterruptWithSinkAndSource() {
		final Jio<Integer, Throwable, String> jio = Jio.fromFunction(Object::toString);
		jio.interrupt(new IllegalArgumentException());

		assertCalled(jio, 3, (c,i) -> {
			assertNotNull(c);
			assertTrue(c.getError() instanceof IllegalArgumentException);
			assertNull(c.getCause());
			assertNull(i);
		});
	}
}
