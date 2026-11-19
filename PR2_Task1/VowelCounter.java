import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Практическая работа №2, задание №1.
 *
 * Программа разбивает файл на N равных частей, читает каждую часть в отдельном потоке,
 * считает в ней гласные (a, e, i, o, u) и передаёт результат главному потоку,
 * который суммирует значения и выводит итог.
 *
 * Особенности реализации:
 *  - в теле потока нет ни одного if (условия вынесены в заголовок цикла, подсчёт идёт
 *    через таблицу перевода байта в 0/1 и простое сложение);
 *  - каждый поток работает со своим FileChannel и своим буфером, поэтому разделяемых
 *    данных при чтении нет; общий только приёмник результатов (синхронизированный);
 *  - ошибки создания/запуска потоков и ошибки ввода-вывода не теряются, а доходят до main.
 *
 * Запуск: java VowelCounter [количество потоков] [путь к файлу]
 * Если файл не существует, создаётся демонстрационный large_text.txt.
 */
public final class VowelCounter {

    /** Размер буфера чтения (64 КиБ): меньше системных вызовов, не вытесняет кэш. */
    private static final int BUFFER_SIZE = 1 << 16;

    /** Буквы, которые считаются гласными. */
    private static final char[] VOWELS = {'a', 'e', 'i', 'o', 'u', 'A', 'E', 'I', 'O', 'U'};

    /**
     * Таблица «байт -> признак гласной»: 1 — гласная, 0 — нет.
     * Заменяет условные операторы: подсчёт превращается в сложение.
     */
    private static final int[] IS_VOWEL = buildVowelTable();

    private VowelCounter() {
        // утилитарный класс
    }

    private static int[] buildVowelTable() {
        int[] table = new int[256];
        for (char c : VOWELS) {
            table[c] = 1;
        }
        return table;
    }

    public static void main(String[] args) {
        int threads = 4;
        Path file = Path.of("large_text.txt");

        try {
            if (args.length > 0) {
                threads = Integer.parseInt(args[0].trim());
            }
            if (args.length > 1) {
                file = Path.of(args[1]);
            }
        } catch (NumberFormatException e) {
            System.err.println("Не удалось разобрать количество потоков, используется 4: " + e.getMessage());
            threads = 4;
        }
        threads = Math.max(1, threads);

        try {
            long size = ensureFile(file);

            // Границы частей: chunk = ceil(size / threads), последняя часть обрезается по size.
            long chunk = Math.max(1L, (size + threads - 1) / threads);

            SharedResult result = new SharedResult(threads);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] workers = new Thread[threads];

            long startedAt = System.nanoTime();

            for (int i = 0; i < threads; i++) {
                long start = (long) i * chunk;
                long end = Math.min(start + chunk, size);
                long length = Math.max(0L, end - start);
                try {
                    workers[i] = Thread.ofPlatform()
                            .name("vowel-reader-" + i)
                            .start(new PartReader(file, start, length, i, result, failure));
                } catch (Throwable e) {
                    // Ошибка создания/запуска потока: запоминаем первую и продолжаем с остальными.
                    failure.compareAndSet(null, e);
                    System.err.println("Не удалось запустить поток " + i + ": " + e);
                }
            }

            for (Thread worker : workers) {
                if (worker == null) {
                    continue; // поток не был создан, объединять нечего
                }
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Ожидание потоков прервано, продолжаем объединение результатов");
                }
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            for (int i = 0; i < threads; i++) {
                long start = (long) i * chunk;
                long end = Math.min(start + chunk, size);
                System.out.printf("Поток %2d: байты [%d, %d) — %d гласных%n",
                        i, start, end, result.part(i));
            }

            Throwable error = failure.get();
            if (error != null) {
                System.err.println("Обработка завершилась с ошибкой, итог недостоверен:");
                error.printStackTrace();
                return;
            }

            System.out.printf("Файл: %s (%d байт), частей: %d, время: %d мс%n",
                    file, size, threads, elapsedMs);
            System.out.println("Всего гласных: " + result.total());
        } catch (IOException e) {
            System.err.println("Не удалось прочитать файл " + file + ": " + e.getMessage());
        }
    }

    /** Проверяет наличие файла, при необходимости создаёт демонстрационный, возвращает размер. */
    private static long ensureFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeSample(file);
            System.out.println("Файл не найден, создан демонстрационный: " + file.toAbsolutePath());
        }
        return Files.size(file);
    }

    private static void writeSample(Path file) throws IOException {
        final int lines = 40_000; // около 3 МБ текста
        final String sample =
                "The quick brown fox jumps over the lazy dog. Java threads read files in parallel.\n";
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines; i++) {
                writer.write(sample);
            }
        }
    }

    /**
     * Разделяемый приёмник результатов.
     *
     * Синхронизация нужна потому, что потоки пишут в один массив, а главный поток читает его
     * после join(). Методы synchronized дают ещё и гарантию видимости (happens-before).
     */
    private static final class SharedResult {
        private final long[] counts;

        SharedResult(int parts) {
            this.counts = new long[parts];
        }

        synchronized void put(int part, long count) {
            counts[part] = count;
        }

        synchronized long part(int index) {
            return counts[index];
        }

        synchronized long total() {
            long sum = 0L;
            for (long count : counts) {
                sum += count;
            }
            return sum;
        }
    }

    /** Поток, читающий свою часть файла. */
    private static final class PartReader implements Runnable {
        private final Path file;
        private final long start;
        private final long length;
        private final int part;
        private final SharedResult result;
        private final AtomicReference<Throwable> failure;

        PartReader(Path file, long start, long length, int part,
                   SharedResult result, AtomicReference<Throwable> failure) {
            this.file = file;
            this.start = start;
            this.length = length;
            this.part = part;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public void run() {
            long vowels = 0L;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                channel.position(start);
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                long remaining = length;
                int read = 1;

                // Условия стоят в заголовке цикла, а не внутри тела: выходим, когда часть
                // прочитана или данные исчерпаны. В теле только сложение без ветвлений.
                while (remaining > 0 && read > 0) {
                    // Ключевой момент: буфер ограничивается остатком части, иначе последний
                    // read() захватит байты из участка соседнего потока и они будут посчитаны дважды.
                    int toRead = (int) Math.min(BUFFER_SIZE, remaining);
                    buffer.clear();
                    buffer.limit(toRead);
                    read = channel.read(buffer);

                    // read == -1 (конец файла) превращается в 0 без условного оператора.
                    int counted = Math.max(read, 0);
                    byte[] bytes = buffer.array();
                    // & 0xFF — перевод signed byte в диапазон 0..255.
                    // Байты многобайтных символов UTF-8 >= 0x80, в таблице это 0,
                    // поэтому разрыв символа на границе частей не влияет на подсчёт.
                    for (int i = 0; i < counted; i++) {
                        vowels += IS_VOWEL[bytes[i] & 0xFF];
                    }
                    remaining -= counted;
                }
            } catch (Throwable t) {
                // Ошибку запоминаем: главный поток о ней сообщит.
                failure.compareAndSet(null, t);
            }
            result.put(part, vowels);
        }
    }
}
