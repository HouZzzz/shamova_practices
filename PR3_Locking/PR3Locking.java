import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Практическая работа №3. Блокирующие переменные и механизмы синхронизации.
 *
 * Шаги работы:
 *   1 — общий счётчик под lock (в Java этому соответствует synchronized-блок);
 *   2 — Monitor: synchronized-методы, реентерабельность и условные переменные (wait/notify);
 *   3 — Mutex: ReentrantLock и межпроцессная блокировка файла (FileLock);
 *   4 — Semaphore: одновременно не более двух потоков в критической секции;
 *   5 — дедлок: демонстрация, обнаружение и исправление;
 *   6 — сравнение производительности вариантов синхронизации.
 *
 * Запуск: java PR3Locking [1|2|3|3-hold|4|5|6|all]
 *
 * Примечание: в Java конструкции "lock из C#" соответствует synchronized,
 * а "Monitor.Enter/Exit" — байткод monitorenter/monitorexit, в который компилируется synchronized.
 */
public final class PR3Locking {

    /** Количество потоков-счётчиков в шагах 1–3. */
    private static final int THREADS = 4;

    /** Сколько приращений делает каждый поток в шагах 1–3. */
    private static final int ITERATIONS = 250_000;

    private PR3Locking() {
    }

    public static void main(String[] args) {
        String mode = (args.length > 0) ? args[0].trim() : "all";
        try {
            switch (mode) {
                case "1" -> step1Lock();
                case "2" -> step2Monitor();
                case "3" -> step3Mutex();
                case "3-hold" -> holdFileLock();
                case "4" -> step4Semaphore();
                case "5" -> step5Deadlock();
                case "6" -> step6Compare();
                case "all" -> {
                    step1Lock();
                    step2Monitor();
                    step3Mutex();
                    step4Semaphore();
                    step5Deadlock();
                    step6Compare();
                }
                default -> System.out.println("Режимы: 1 | 2 | 3 | 3-hold | 4 | 5 | 6 | all");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Выполнение прервано: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Ошибка ввода-вывода: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Шаг 1

    /**
     * Общий счётчик под lock. В C# конструкция
     *     lock (obj) { counter++; }
     * компилируется в Monitor.Enter(obj) / try / finally Monitor.Exit(obj).
     * В Java тот же смысл у synchronized-блока: компилятор вставляет monitorenter/monitorexit
     * с гарантированным выходом из монитора при любом завершении блока.
     */
    private static void step1Lock() throws InterruptedException {
        header("Шаг 1. Синхронизация с lock (synchronized-блок)");

        final Object lock = new Object();
        final long[] counter = {0L};

        Thread[] workers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            workers[i] = Thread.ofPlatform().name("lock-" + i).start(() -> {
                for (int k = 0; k < ITERATIONS; k++) {
                    // Критическая секция: только один поток одновременно.
                    synchronized (lock) {
                        counter[0]++;
                    }
                }
            });
        }
        joinAll(workers);

        long expected = (long) THREADS * ITERATIONS;
        System.out.printf("Потоков: %d, приращений на поток: %d%n", THREADS, ITERATIONS);
        System.out.printf("Ожидалось: %d, получено: %d — %s%n",
                expected, counter[0], (expected == counter[0] ? "корректно" : "ОШИБКА"));

        // Контрольный прогон без блокировки: показывает, зачем синхронизация нужна.
        final LongUnsync unsync = new LongUnsync();
        Thread[] racy = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            racy[i] = Thread.ofPlatform().start(() -> {
                for (int k = 0; k < ITERATIONS; k++) {
                    unsync.value++; // гонка данных: ++ не атомарен
                }
            });
        }
        joinAll(racy);
        System.out.printf("Без блокировки: %d вместо %d (потерянные обновления)%n%n",
                unsync.value, expected);
    }

    /** Обёртка без синхронизации — только для сравнения. */
    private static final class LongUnsync {
        volatile long value;
    }

    // ---------------------------------------------------------------- Шаг 2

    /**
     * Повтор шага 1 средствами Monitor.
     *
     * В Java отдельного класса Monitor нет: monitor — это встроенный механизм каждого объекта.
     * synchronized-метод компилируется в monitorenter по this на входе и monitorexit на выходе
     * (в том числе при исключении). По сравнению с C# lock монитор даёт дополнительно:
     *   - реентерабельность (поток может повторно войти в тот же монитор);
     *   - условные переменные wait/notifyAll для ожидания события;
     *   - явное владение счётчиком входов, освобождаемым ровно столько же раз.
     */
    private static void step2Monitor() throws InterruptedException {
        header("Шаг 2. Monitor: synchronized-методы, реентерабельность, wait/notify");

        final MonitorCounter counter = new MonitorCounter();

        Thread[] workers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            workers[i] = Thread.ofPlatform().name("monitor-" + i).start(() -> {
                for (int k = 0; k < ITERATIONS; k++) {
                    counter.increment(); // монитор захватывается на входе в метод
                }
            });
        }
        joinAll(workers);

        long expected = (long) THREADS * ITERATIONS;
        System.out.printf("Счётчик через synchronized-методы: %d (ожидалось %d) — %s%n",
                counter.value(), expected, (counter.value() == expected ? "корректно" : "ОШИБКА"));

        // Реентерабельность: поток, уже владеющий монитором, входит в него повторно.
        int depth = counter.reentrantDepth(5);
        System.out.println("Глубина повторного входа в монитор: " + depth + " — реентерабельность подтверждена");

        // Условная переменная монитора: главный поток ждёт события, не опрашивая счётчик в цикле.
        Handoff handoff = new Handoff();
        Thread producer = Thread.ofPlatform().name("producer").start(() -> {
            for (int i = 1; i <= 5; i++) {
                handoff.push(i);
                sleepMillis(20);
            }
        });
        long received = handoff.awaitSum(15L); // 1+2+3+4+5
        producer.join();
        System.out.printf("wait/notify: главный поток дождался суммы %d, не выполняя опрос%n%n", received);
    }

    /** Счётчик с монитором на каждом методе. */
    private static final class MonitorCounter {
        private long value;

        synchronized void increment() {
            value++;
        }

        synchronized long value() {
            return value;
        }

        /** Рекурсивный вход в тот же монитор: если бы монитор не был реентерабельным, поток завис бы. */
        synchronized int reentrantDepth(int depth) {
            return depth <= 0 ? 0 : 1 + reentrantDepth(depth - 1);
        }
    }

    /** Демонстрация wait/notify: ожидание условия без активного опроса. */
    private static final class Handoff {
        private long sum;
        private boolean ready;

        synchronized void push(int value) {
            sum += value;
            ready = (value == 5);
            notifyAll(); // будим ожидающего
        }

        synchronized long awaitSum(long expected) throws InterruptedException {
            while (!ready) {
                wait(); // атомарно отпускает монитор и ждёт сигнала
            }
            return sum;
        }
    }

    // ---------------------------------------------------------------- Шаг 3

    /**
     * Mutex. В Java нет класса Mutex: внутрипроцессное взаимное исключение даёт ReentrantLock,
     * а межпроцессное — блокировка файла (FileLock), которую держит операционная система.
     *
     * Отличие от монитора: ReentrantLock умеет справедливую очередь, tryLock с таймаутом,
     * прерываемый захват и несколько независимых условных переменных (Condition).
     */
    private static void step3Mutex() throws InterruptedException, IOException {
        header("Шаг 3. Mutex: ReentrantLock и блокировка файла между процессами");

        final ReentrantLock mutex = new ReentrantLock(true); // true — честная очередь ожидания
        final long[] counter = {0L};

        Thread[] workers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            workers[i] = Thread.ofPlatform().name("mutex-" + i).start(() -> {
                for (int k = 0; k < ITERATIONS; k++) {
                    mutex.lock();
                    try {
                        counter[0]++;
                    } finally {
                        mutex.unlock(); // освобождение обязано быть в finally
                    }
                }
            });
        }
        joinAll(workers);

        long expected = (long) THREADS * ITERATIONS;
        System.out.printf("ReentrantLock: %d (ожидалось %d) — %s%n",
                counter[0], expected, (counter[0] == expected ? "корректно" : "ОШИБКА"));

        // Межпроцессная часть: FileLock принадлежит процессу, а не потоку.
        Path lockFile = Path.of("app.lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            FileLock held = channel.tryLock();
            if (held == null) {
                System.out.println("Файл " + lockFile.toAbsolutePath() + " уже заблокирован другим процессом");
                return;
            }
            System.out.println("Захвачен эксклюзивный FileLock на " + lockFile.toAbsolutePath());

            // Второй поток этого же процесса: ОС-лок уже наш, JVM сообщает об этом исключением,
            // потому что FileLock действует на процесс целиком, а не на отдельный поток.
            Thread rival = Thread.ofPlatform().name("rival").start(() -> {
                try {
                    FileLock second = channel.tryLock();
                    System.out.println("Второй поток тоже захватил блокировку: " + (second != null));
                } catch (OverlappingFileLockException e) {
                    System.out.println("Второй поток получил OverlappingFileLockException — "
                            + "блокировка принадлежит процессу, а не потоку");
                } catch (IOException e) {
                    System.out.println("Второй поток: ошибка ввода-вывода — " + e.getMessage());
                }
            });
            rival.join();

            held.release();
            System.out.println("FileLock освобождён");
        } finally {
            try {
                Files.deleteIfExists(lockFile);
            } catch (IOException ignored) {
                // служебный файл останется, на результат это не влияет
            }
        }
        System.out.println("Для проверки между процессами запустите во втором терминале:");
        System.out.println("  java PR3Locking 3-hold   (держит блокировку 20 с), затем в третьем: java PR3Locking 3");
        System.out.println();
    }

    /** Режим 3-hold: удерживает файловую блокировку, чтобы второй процесс увидел занятость. */
    private static void holdFileLock() throws IOException, InterruptedException {
        header("Шаг 3. Удержание межпроцессной блокировки");
        Path lockFile = Path.of("app.lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            FileLock lock = channel.tryLock();
            if (lock == null) {
                System.out.println("Блокировка занята другим процессом — мьютекс работает между процессами");
                return;
            }
            System.out.println("Блокировка захвачена, удерживаю 20 секунд...");
            TimeUnit.SECONDS.sleep(20);
            lock.release();
            System.out.println("Блокировка освобождена");
        }
    }

    // ---------------------------------------------------------------- Шаг 4

    /**
     * Semaphore ограничивает число одновременных входов в критическую секцию.
     * В отличие от мьютекса, разрешений может быть несколько и владение ими не закреплено
     * за потоком: любое поток может сделать release(), а не только захвативший.
     */
    private static void step4Semaphore() throws InterruptedException {
        header("Шаг 4. Semaphore: не более двух потоков одновременно");

        final int permits = 2;
        final int visitors = 6;
        final Semaphore semaphore = new Semaphore(permits, true); // true — честный порядок выдачи
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();

        Thread[] workers = new Thread[visitors];
        for (int i = 0; i < visitors; i++) {
            final int id = i + 1;
            workers[i] = Thread.ofPlatform().name("visitor-" + id).start(() -> {
                try {
                    System.out.printf("Поток %d ожидает разрешение%n", id);
                    semaphore.acquire();
                    try {
                        int now = active.incrementAndGet();
                        maxActive.accumulateAndGet(now, Math::max);
                        System.out.printf("Поток %d вошёл в секцию (активных: %d)%n", id, now);
                        sleepMillis(300); // имитация работы с ресурсом
                    } finally {
                        active.decrementAndGet();
                        semaphore.release(); // возвращаем разрешение в любом случае
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        joinAll(workers);

        System.out.printf("Разрешений: %d, максимальное число одновременных входов: %d — %s%n%n",
                permits, maxActive.get(), (maxActive.get() <= permits ? "ограничение соблюдено" : "ОШИБКА"));
    }

    // ---------------------------------------------------------------- Шаг 5

    /**
     * Дедлок: два потока захватывают две блокировки в противоположном порядке.
     * Затем тот же сценарий решается упорядочиванием захвата и tryLock с таймаутом.
     */
    private static void step5Deadlock() throws InterruptedException {
        header("Шаг 5. Дедлок и способы его избежать");

        final Object lockA = new Object();
        final Object lockB = new Object();

        // Оба потока — демоны: зависшие навсегда потоки не должны мешать завершению JVM.
        Thread first = Thread.ofPlatform().daemon(true).name("deadlock-first").start(() -> {
            synchronized (lockA) {
                System.out.println("Поток 1: захватил A, ждёт B");
                sleepMillis(200);
                synchronized (lockB) {
                    System.out.println("Поток 1: захватил и B");
                }
            }
        });

        Thread second = Thread.ofPlatform().daemon(true).name("deadlock-second").start(() -> {
            synchronized (lockB) {
                System.out.println("Поток 2: захватил B, ждёт A");
                sleepMillis(200);
                synchronized (lockA) {
                    System.out.println("Поток 2: захватил и A");
                }
            }
        });

        // Ждём не бесконечно: если поток не завершился, значит он заблокирован.
        first.join(1500);
        second.join(1500);
        boolean stuck = first.isAlive() && second.isAlive();
        System.out.println("Оба потока не завершились: " + stuck + " — взаимная блокировка");

        // Штатное обнаружение: JVM сама находит цикл ожидания мониторов.
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        long[] deadlocked = mxBean.findDeadlockedThreads();
        if (deadlocked != null) {
            System.out.println("findDeadlockedThreads нашёл потоки: " + deadlocked.length);
            for (ThreadInfo info : mxBean.getThreadInfo(deadlocked)) {
                // getLockName() — монитор, которого ждёт поток, getLockOwnerName() — кто его держит.
                System.out.printf("  %s ожидает %s (держит %s)%n",
                        info.getThreadName(), info.getLockName(), info.getLockOwnerName());
            }
        } else {
            System.out.println("Цикл ожидания не обнаружен");
        }

        // Исправление 1: одинаковый порядок захвата у всех потоков.
        // Исправление 2: tryLock с таймаутом — поток отступает и повторяет попытку.
        System.out.println("--- Исправленный вариант: tryLock с таймаутом ---");
        ReentrantLock left = new ReentrantLock();
        ReentrantLock right = new ReentrantLock();

        Thread fixed = Thread.ofPlatform().name("fixed").start(() -> {
            try {
                boolean gotLeft = left.tryLock(300, TimeUnit.MILLISECONDS);
                boolean gotRight = gotLeft && right.tryLock(300, TimeUnit.MILLISECONDS);
                if (gotLeft && gotRight) {
                    System.out.println("Исправленный поток: обе блокировки получены, работа выполнена");
                } else {
                    System.out.println("Исправленный поток: не дождался блокировки за 300 мс и отступил "
                            + "вместо вечного ожидания");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (right.isHeldByCurrentThread()) {
                    right.unlock();
                }
                if (left.isHeldByCurrentThread()) {
                    left.unlock();
                }
            }
        });
        fixed.join();

        System.out.println("Зависшие демоны остаются в JVM до её завершения — это и есть цена дедлока");
        System.out.println();
    }

    // ---------------------------------------------------------------- Шаг 6

    /**
     * Сравнение вариантов синхронизации одного и того же счётчика.
     * Числа зависят от машины; важен порядок величин и то, что без блокировки результат неверен.
     */
    private static void step6Compare() throws InterruptedException {
        header("Шаг 6. Сравнение производительности");

        final int threads = 8;
        final int iterations = 500_000;
        final long expected = (long) threads * iterations;

        final long[] plain = {0L};
        final Object monitor = new Object();
        final long[] underMonitor = {0L};
        final ReentrantLock mutex = new ReentrantLock();
        final long[] underMutex = {0L};
        final AtomicLong atomic = new AtomicLong();

        long tPlain = measure(threads, () -> {
            for (int i = 0; i < iterations; i++) {
                plain[0]++;
            }
        });
        long tMonitor = measure(threads, () -> {
            for (int i = 0; i < iterations; i++) {
                synchronized (monitor) {
                    underMonitor[0]++;
                }
            }
        });
        long tMutex = measure(threads, () -> {
            for (int i = 0; i < iterations; i++) {
                mutex.lock();
                try {
                    underMutex[0]++;
                } finally {
                    mutex.unlock();
                }
            }
        });
        long tAtomic = measure(threads, () -> {
            for (int i = 0; i < iterations; i++) {
                atomic.incrementAndGet();
            }
        });

        System.out.printf("Потоков: %d, приращений на поток: %d, ожидаемое значение: %d%n",
                threads, iterations, expected);
        System.out.printf("%-28s %14s %12s %10s%n", "Вариант", "Результат", "Ошибка", "Время, мс");
        printRow("без блокировки", plain[0], expected, tPlain);
        printRow("synchronized", underMonitor[0], expected, tMonitor);
        printRow("ReentrantLock", underMutex[0], expected, tMutex);
        printRow("AtomicLong", atomic.get(), expected, tAtomic);
        System.out.println();
        System.out.println("Вывод: атомарные операции и lock дают правильный результат;");
        System.out.println("без синхронизации часть приращений теряется. Время первого замера включает");
        System.out.println("разогрев JIT, поэтому абсолютные значения — ориентировочные.");
    }

    private static long measure(int threads, Runnable body) throws InterruptedException {
        Thread[] workers = new Thread[threads];
        long startedAt = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            workers[i] = Thread.ofPlatform().start(body);
        }
        joinAll(workers);
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static void printRow(String name, long actual, long expected, long millis) {
        System.out.printf("%-28s %14d %12d %10d%n", name, actual, expected - actual, millis);
    }

    // ------------------------------------------------------------- Утилиты

    private static void header(String title) {
        System.out.println("=== " + title + " ===");
    }

    private static void joinAll(Thread[] threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
