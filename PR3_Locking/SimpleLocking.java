import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Практическая работа №3 (простая версия): пять шагов синхронизации.
 *
 * Соответствие механизмов из методички (C#) и Java:
 *   lock            -> synchronized (obj) { ... }
 *   Monitor.Enter   -> synchronized-метод (monitorenter/monitorexit)
 *   Mutex           -> ReentrantLock (внутри процесса), FileLock (между процессами)
 *   Semaphore       -> Semaphore
 *
 * Запуск: java SimpleLocking
 */
public class SimpleLocking {

    private static final int THREADS = 4;
    private static final int N = 100_000; // приращений на поток

    public static void main(String[] args) throws InterruptedException {
        step1Lock();
        step2Monitor();
        step3Mutex();
        step4Semaphore();
        step5Deadlock();
    }

    /** Шаг 1. lock: критическая секция защищает общий счётчик. */
    private static void step1Lock() throws InterruptedException {
        Object lock = new Object();
        long[] counter = {0};

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int k = 0; k < N; k++) {
                    synchronized (lock) { // аналог lock (lock) { counter++; } в C#
                        counter[0]++;
                    }
                }
            });
        }
        run(threads);
        System.out.println("Шаг 1 (lock):     " + counter[0] + " из " + (long) THREADS * N);
    }

    /** Обычный счётчик с монитором на методе — то, что в C# делают через Monitor.Enter/Exit. */
    private static class Counter {
        private long value;

        synchronized void inc() {
            value++; // монитор this захвачен на время метода
        }

        /** Повторный вход в свой же монитор: монитор реентерабелен, поток не блокирует себя. */
        synchronized int depth(int n) {
            return n == 0 ? 0 : 1 + depth(n - 1);
        }

        long get() {
            return value;
        }
    }

    /**
     * Шаг 2. Monitor: synchronized-метод. Отличие от lock — тот же монитор даёт
     * повторный вход (проверяем depth) и ожидание события через wait/notify.
     */
    private static void step2Monitor() throws InterruptedException {
        Counter counter = new Counter();
        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int k = 0; k < N; k++) {
                    counter.inc();
                }
            });
        }
        run(threads);
        System.out.println("Шаг 2 (Monitor):  " + counter.get() + " из " + (long) THREADS * N
                + ", глубина повторного входа: " + counter.depth(3));
    }

    /** Шаг 3. Mutex: ReentrantLock. Между процессами роль мьютекса играет FileLock. */
    private static void step3Mutex() throws InterruptedException {
        ReentrantLock mutex = new ReentrantLock();
        long[] counter = {0};

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int k = 0; k < N; k++) {
                    mutex.lock();
                    try {
                        counter[0]++;
                    } finally {
                        mutex.unlock(); // освобождать нужно всегда, даже при исключении
                    }
                }
            });
        }
        run(threads);
        System.out.println("Шаг 3 (Mutex):    " + counter[0] + " из " + (long) THREADS * N);
    }

    /** Шаг 4. Semaphore: одновременно в ресурсе не больше двух потоков. */
    private static void step4Semaphore() throws InterruptedException {
        Semaphore semaphore = new Semaphore(2);
        AtomicInteger active = new AtomicInteger();

        Thread[] threads = new Thread[6];
        for (int i = 0; i < threads.length; i++) {
            int id = i + 1;
            threads[i] = new Thread(() -> {
                try {
                    semaphore.acquire(); // ждём свободное разрешение
                    try {
                        System.out.println("  поток " + id + " в ресурсе, активных: " + active.incrementAndGet());
                        Thread.sleep(200); // имитация работы
                    } finally {
                        active.decrementAndGet();
                        semaphore.release(); // возвращаем разрешение
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        run(threads);
        System.out.println("Шаг 4 (Semaphore): разрешений 2, потоков 6 — лишние ждали");
    }

    /** Шаг 5. Дедлок: две блокировки захватываются в разном порядке. */
    private static void step5Deadlock() throws InterruptedException {
        Object a = new Object();
        Object b = new Object();

        Thread first = new Thread(() -> {
            synchronized (a) {
                sleep();
                synchronized (b) {
                    System.out.println("  поток 1 дошёл до конца");
                }
            }
        });
        Thread second = new Thread(() -> {
            synchronized (b) {
                sleep();
                synchronized (a) {
                    System.out.println("  поток 2 дошёл до конца");
                }
            }
        });
        first.setDaemon(true); // зависшие потоки не должны держать программу
        second.setDaemon(true);
        first.start();
        second.start();

        first.join(1000);
        second.join(1000);
        System.out.println("Шаг 5 (дедлок): оба потока зависли = " + (first.isAlive() && second.isAlive()));
        System.out.println("  лечение: захватывать блокировки всегда в одном порядке или брать tryLock с таймаутом");
    }

    /** Запускает все потоки и ждёт завершения каждого. */
    private static void run(Thread[] threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
