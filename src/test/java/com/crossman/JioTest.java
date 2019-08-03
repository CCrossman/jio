package com.crossman;

import com.crossman.util.CheckedSupplier;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
			assertEquals("Bad request", ex);
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
		Jio<Void,Void,String> jio = Jio.effect(new Supplier<String>() {
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
			assertTrue(ex instanceof ArithmeticException);
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
			assertEquals("Authentication failed", ex);
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
			assertEquals("Bad request", ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMappableFromEvalAlways() {
		Jio<Void,String,Integer> jio1 = Jio.effect(() -> 5);
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
			assertEquals(Integer.valueOf(11), ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioMapErrorFromEvalAlways() {
		Jio<Void,String,Integer> jio1 = Jio.effect(() -> 5);
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
			assertEquals("hello world", ex);
			assertNull(a);
		});
	}

	@Test
	public void testJioFlatMapFromFailure() {
		Jio<Void,String,String> jio1 = Jio.fail("Bad Request");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertEquals("Bad Request", ex);
			assertNull(a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		jio3.unsafeRun(null, (ex,a) -> {
			assertEquals("Bad Request", ex);
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
		Jio<Void,String,String> jio1 = Jio.effect(() -> "Foo");
		Jio<Void,String,String> jio2 = jio1.flatMap(s -> Jio.success(s.toUpperCase()));
		jio2.unsafeRun(null, (ex,a) -> {
			assertNull(ex);
			assertEquals("FOO", a);
		});

		Jio<Void,String,String> jio3 = jio1.flatMap(s -> Jio.fail(s.toLowerCase()));
		jio3.unsafeRun(null, (ex,a) -> {
			assertEquals("foo", ex);
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
			assertEquals("xx4", ex);
			assertNull(a);
		});
	}
}
