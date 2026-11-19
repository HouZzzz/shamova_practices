import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Практическая работа №2, задание 1 (простая версия).
 *
 * Файл делится на 4 равные части, каждый поток считает гласные (a, e, i, o, u)
 * в своей части, главный поток складывает результаты.
 *
 * Запуск: java SimpleVowelCounter
 */
public class SimpleVowelCounter {

    private static final int THREADS = 4;
    private static final int BUFFER = 8192;

    /**
     * Таблица «байт → признак гласной»: 1 — гласная, 0 — нет.
     * Благодаря ей в потоке нет ни одного if — только сложение.
     * Байты русского текста в UTF-8 попадают в диапазон 128..255, где всегда 0,
     * поэтому разрыв символа на границе частей не мешает подсчёту.
     */
    private static final int[] VOWEL = new int[256];

    static {
        for (char c : "aeiouAEIOU".toCharArray()) {
            VOWEL[c] = 1;
        }
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of("large_text.txt");
        if (!Files.exists(file)) { // демонстрационный файл, если своего нет
            Files.writeString(file, "The quick brown fox jumps over the lazy dog.\n".repeat(100_000));
        }

        long size = Files.size(file);
        long chunk = (size + THREADS - 1) / THREADS; // размер одной части
        long[] parts = new long[THREADS];
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            int id = i;
            long from = id * chunk;
            long length = Math.min(chunk, size - from);

            threads[i] = new Thread(() -> {
                try {
                    parts[id] = countVowels(file, from, length);
                } catch (IOException e) {
                    System.err.println("Поток " + id + ": ошибка чтения — " + e.getMessage());
                }
            });
            threads[i].start();
        }

        // Блокировка не нужна: каждый поток пишет в свою ячейку parts[id],
        // а join() гарантирует, что главный поток увидит записи всех потоков.
        for (Thread thread : threads) {
            thread.join();
        }

        long total = 0;
        for (long part : parts) {
            total += part;
        }

        System.out.println("Файл " + file + ": " + size + " байт, частей: " + THREADS);
        System.out.println("Гласных всего: " + total);
    }

    /** Считает гласные в диапазоне [from, from + length). */
    private static long countVowels(Path file, long from, long length) throws IOException {
        long count = 0;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(from);
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER);
            int read = 1;

            while (length > 0 && read > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(BUFFER, length)); // не читаем чужие байты
                read = channel.read(buffer);
                int got = Math.max(read, 0);                  // -1 (конец файла) превращаем в 0

                for (int i = 0; i < got; i++) {
                    count += VOWEL[buffer.get(i) & 0xFF];     // & 0xFF: byte → 0..255
                }
                length -= got;
            }
        }
        return count;
    }
}
