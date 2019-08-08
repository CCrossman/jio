package com.crossman;

import com.crossman.util.CheckedSupplier;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static junit.framework.TestCase.*;

public class JioTest {

	@Test
	public void testJioSuccess() {
		Jio<Void,Void,String> jio = Jio.success("Hello");
		jio.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Hello", a);
		});
	}

	@Test
	public void testJioFailure() {
		Jio<Void,String,Integer> jio = Jio.fail("Bad request");
		jio.unsafeRun(null, (ex,a) -> {
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
	public void testJioSuccessLazyOnlyRunOnceWhenNecessary() throws InterruptedException {
		final List<String> out = new ArrayList<>();
		Jio<Void,Void,String> jio = Jio.successLazy(new Supplier<String>() {
			@Override
			public String get() {
				out.add("Bang!");
				return "BLAM!";
			}
		});

		assertEquals(0, out.size());

		jio.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("BLAM!", a);
		});
		Thread.sleep(100L);
		assertEquals(1, out.size());

		jio.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("BLAM!", a);
		});
		Thread.sleep(100L);
		assertEquals(1, out.size());
	}

	@Test
	public void testJioEffectRunsEveryTime() throws InterruptedException {
		final List<String> out = new ArrayList<>();
		Jio<Void,Void,String> jio = Jio.effectTotal(new Supplier<String>() {
			@Override
			public String get() {
				out.add("Hello");
				return "World";
			}
		});

		assertEquals(0, out.size());

		jio.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("World", a);
		});
		Thread.sleep(100L);
		assertEquals(1, out.size());

		jio.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("World", a);
		});
		Thread.sleep(100L);
		assertEquals(2, out.size());
	}

	@Test
	public void testJioFromTrying() {
		Jio<Void,ArithmeticException,Integer> jio = Jio.fromTrying(new CheckedSupplier<ArithmeticException, Integer>() {
			@Override
			public Integer get() throws ArithmeticException {
				return 42 / 0;
			}
		});
		assertNotNull(jio);

		jio.unsafeRun(null, (ex,a) -> {
			assertNotNull(ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFromFunction() {
		Jio<Integer,Throwable,Integer> jio = Jio.fromFunction(i -> i * (i+1));
		jio.unsafeRun(5, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(30), a);
		});
	}

	@Test
	public void testJioFromFuture() {
		final CompletableFuture<String> future = new CompletableFuture<>();
		Jio<Void, CompletionException, String> jio = Jio.fromFuture(future);
		jio.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Hello World!", a);
		});
		future.complete("Hello World!");
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
		jio.unsafeRun(null, (ex,a) -> {
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
		jio.unsafeRun(null, (ex,a) -> {
			assertEquals(new Cause<>("Authentication failed"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMappableFromSuccess() {
		Jio<Void,String,Integer> jio1 = Jio.success(10);
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("X10", a);
		});
	}

	@Test
	public void testJioMappableFromFailure() {
		Jio<Void,String,Integer> jio1 = Jio.fail("Bad request");
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		jio2.unsafeRun(null, (ex,a) -> {
			assertEquals(new Cause<>("Bad request"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMappableFromEvalAlways() {
		Jio<Void,String,Integer> jio1 = Jio.effectTotal(() -> 5);
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("X5", a);
		});
	}

	@Test
	public void testJioMappableFromPromise() {
		Jio.Promise<Void,String,Integer> jio1 = Jio.promise();
		Jio<Void,String,String> jio2 = jio1.map(i -> "X" + i);
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("X4", a);
		});
		jio1.setDelegate(Jio.success(4));
	}

	@Test
	public void testJioMappableFromSinkAndSource() {
		Jio<Integer,Throwable,Integer> jio1 = Jio.fromFunction(i -> i + 1);
		Jio<Integer,Throwable,String> jio2 = jio1.map(i -> "X" + i);
		jio2.unsafeRun(3, (ex,a) -> {
			assertNull(ex);
			assertEquals("X4", a);
		});
	}

	@Test
	public void testJioMapErrorFromSuccess() {
		Jio<Void,String,Integer> jio1 = Jio.success(10);
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(10),a);
		});
	}

	@Test
	public void testJioMapErrorFromFailure() {
		Jio<Void,String,Integer> jio1 = Jio.fail("Bad request");
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		jio2.unsafeRun(null, (ex,a) -> {
			assertEquals(new Cause<>(11), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMapErrorFromEvalAlways() {
		Jio<Void,String,Integer> jio1 = Jio.effectTotal(() -> 5);
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(5), a);
		});
	}

	@Test
	public void testJioMapErrorFromPromise() {
		Jio.Promise<Void,String,Integer> jio1 = Jio.promise();
		Jio<Void,Integer,Integer> jio2 = jio1.mapError(s -> s.length());
		jio2.unsafeRun(null, (ex,a) -> {
			assertEquals(Integer.valueOf(4),ex);
			assertNull(a);
		});
		jio1.setDelegate(Jio.fail("hiya"));
	}

	@Test
	public void testJioMapErrorFromSinkAndSource() {
		Jio<Integer,Throwable,Integer> jio1 = Jio.fromFunction(i -> i + 1);
		Jio<Integer,IllegalArgumentException,Integer> jio2 = jio1.mapError(IllegalArgumentException::new);
		jio2.unsafeRun(3, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(4), a);
		});
	}

	@Test
	public void testJioFlatMapFromSuccess() {
		Jio<Void,String,String> jio1 = Jio.success("Hello World");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("HELLO WORLD", a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		jio3.unsafeRun(null, (ex,a) -> {
			assertEquals(new Cause<>("hello world"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFlatMapFromFailure() {
		Jio<Void,String,String> jio1 = Jio.fail("Bad Request");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertEquals(new Cause<>("Bad Request"), ex);
			assertNull(a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		jio3.unsafeRun(null, (ex,a) -> {
			assertEquals(new Cause<>("Bad Request"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFlatMapFromPromise() {
		Jio.Promise<Void,String,String> jio1 = Jio.promise();
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("FOOBAR", a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.success(s.toLowerCase()));
		jio3.unsafeRun(null, (ex,a) -> {
			assertEquals("foobar", ex);
			assertNull(a);
		});
		jio1.setDelegate(Jio.success("FooBar"));
	}

	@Test
	public void testJioFlatMapFromEvalAlways() {
		Jio<Void,String,String> jio1 = Jio.effectTotal(() -> "Foo");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("FOO", a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		jio3.unsafeRun(null, (ex,a) -> {
			assertEquals(new Cause<>("foo"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFlatMapFromSinkAndSource() {
		Jio<Integer, String, String> jio1 = Jio.<Integer,Throwable,String>fromFunction(i -> "Xx" + i).mapError(t -> t.toString());
		Jio<Integer, String, String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		jio2.unsafeRun(5, (ex,a) -> {
			assertNull(ex);
			assertEquals("XX5", a);
		});

		Jio<Integer, String, String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		jio3.unsafeRun(4, (ex,a) -> {
			assertEquals(new Cause<>("xx4"), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioCatchAllFromSuccess() {
		Jio<Void, String, Integer> jio1 = Jio.success(5);
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(5), a);
		});
	}

	@Test
	public void testJioCatchAllFromFailure() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Bad Request");
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(11), a);
		});
	}

	@Test
	public void testJioCatchAllFromPromise() {
		Jio.Promise<Void, String, Integer> jio1 = Jio.promise();
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals(Integer.valueOf(5), a);
		});
		jio1.setDelegate(Jio.fail("Hello"));
	}

	@Test
	public void testJioCatchAllFromEvalAlways() {
		AtomicInteger ai = new AtomicInteger(0);
		Jio<Void, String, Integer> jio1 = Jio.effectTotal(ai::incrementAndGet);
		Jio<Void, Void, Integer> jio2 = jio1.catchAll(s -> Jio.success(s.getError().length()));
		jio2.unsafeRun(null, (ex,a) -> {
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
		jio2.unsafeRun("", ($,i) -> {
			assertNull($);
			assertEquals(Integer.valueOf(-1),i);
		});
		jio2.unsafeRun("Foobar", ($,i) -> {
			assertNull($);
			assertEquals(Integer.valueOf(6), i);
		});
	}

	@Test
	public void testEnsuringOverEffect() {
		final List<String> out = new ArrayList<>();
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.effectTotal(() -> {
			out.add("Finalized!");
			return null;
		}));
		jio2.unsafeRun(null, (ex,a) -> {
			assertEquals(1, out.size());
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testEnsuringOverSinkAndSource() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.fromTotalFunction($ -> null));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testEnsuringOverPromise() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.promise());
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(a);
			assertEquals("Failed!", ex);
		});
	}

	@Test
	public void testEnsuringOverSuccess() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.success(null));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testEnsuringOverFailure() {
		Jio<Void, String, Integer> jio1 = Jio.fail("Failed!");
		Jio<Void, String, Integer> jio2 = jio1.ensuring(Jio.fail(null));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(a);
			assertEquals(new Cause<>("Failed!"), ex);
		});
	}

	@Test
	public void testBracket() {
		Jio<String, FileNotFoundException, File> jio1 = Jio.fromFunction(File::new);
		Jio<String, FileNotFoundException, Long> jio2 = jio1.bracket(f -> Jio.effectTotal(() -> null), f -> Jio.effectTotal(() -> f.length()));
		jio2.unsafeRun("/not/a/file", (ex,a) -> {
			assertNull(ex);
			assertEquals(Long.valueOf(0), a);
		});
	}

	@Test
	public void testZipPar() throws InterruptedException {
		long start = System.currentTimeMillis();
		Jio<Void, InterruptedException, String> jio1 = Jio.effect(() -> {
			Thread.sleep(500L);
			return "Hello";
		});

		Jio<Void, InterruptedException, String> jio2 = Jio.effect(() -> {
			Thread.sleep(600L);
			return "World";
		});

		final AtomicBoolean called = new AtomicBoolean(false);
		jio1.zipPar(jio2, (a,b) -> a + " " + b).unsafeRun(null, (ie,s) -> {
			assertNull(ie);
			assertEquals("Hello World", s);
			long duration = System.currentTimeMillis() - start;
			assertTrue(duration < 700);
			called.set(true);
		});

		Thread.sleep(800);
		assertTrue(called.get());
	}

	@Test
	public void testCollectPar() throws InterruptedException {
		long start = System.currentTimeMillis();
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

		final AtomicBoolean called = new AtomicBoolean(false);
		Jio.collectPar(Arrays.asList(jio1,jio2,jio3)).unsafeRun(null, (ex, lst) -> {
			assertNull(ex);
			assertEquals(lst, Arrays.asList("Hel", "lo W", "orld"));
			long duration = System.currentTimeMillis() - start;
			assertTrue(duration < 700);
			called.set(true);
		});

		Thread.sleep(800);
		assertTrue(called.get());
	}

	@Test
	public void testRaceOk() throws InterruptedException {
		final AtomicBoolean called = new AtomicBoolean(false);
		final Jio<Void, InterruptedException, String> winner = Jio.<Void,InterruptedException,String>effect(() -> {
			Thread.sleep(100);
			return "Hello";
		}).race(Jio.effect(() -> {
			Thread.sleep(200);
			return "World";
		}));
		winner.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Hello", a);
			called.set(true);
		});

		Thread.sleep(300L);
		assertTrue(called.get());
	}

	@Test
	public void testRaceFail() throws InterruptedException {
		final AtomicBoolean called = new AtomicBoolean(false);
		final Jio<Void, String, String> winner = Jio.<Void,String,String>fail("Hello").race(Jio.success("Goodbye"));
		winner.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("Goodbye", a);
			called.set(true);
		});

		Thread.sleep(100L);
		assertTrue(called.get());
	}

	@Test
	public void testRaceBothFail() throws InterruptedException {
		final Jio<Void, String, Integer> winner = Jio.<Void,String,Integer>fail("Hello").race(Jio.fail("Goodbye"));

		final AtomicBoolean called = new AtomicBoolean(false);
		winner.unsafeRun(null, (ex,a) -> {
			assertNotNull(ex);
			assertNull(a);
			called.set(true);
		});

		Thread.sleep(100L);
		assertFalse(called.get());
	}

//	@Test
//	public void testTimeout() throws InterruptedException {
//		final Jio<Void, String, Integer> jio = Jio.promise();
//
//		final AtomicBoolean called = new AtomicBoolean(false);
//		jio.timeout(1, TimeUnit.SECONDS).unsafeRun(null, (s,i) -> {
//			assertNull(s);
//			assertEquals(Optional.empty(), i);
//			called.set(true);
//		});
//
//		Thread.sleep(1100L);
//		assertTrue(called.get());
//	}
}
