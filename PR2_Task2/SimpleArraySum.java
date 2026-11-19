/**
 * Практическая работа №2, задание 2 (простая версия).
 *
 * Массив 1..100 обрабатывают 4 потока по 25 элементов, каждый печатает свою сумму.
 * Главный поток складывает четыре результата.
 *
 * Запуск: java SimpleArraySum
 */
public class SimpleArraySum {

    private static final int SIZE = 100;
    private static final int THREADS = 4;
    private static final int CHUNK = SIZE / THREADS; // 25 элементов на поток

    public static void main(String[] args) throws InterruptedException {
        int[] data = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            data[i] = i + 1; // 1..100
        }

        long[] sums = new long[THREADS];
        Thread[] threads = new Thread[THREADS];

        for (int t = 0; t < THREADS; t++) {
            int id = t;
            int from = id * CHUNK;
            int to = from + CHUNK;

            threads[t] = new Thread(() -> {
                long sum = 0;
                for (int i = from; i < to; i++) { // только цикл и сложение, без if
                    sum += data[i];
                }
                sums[id] = sum;
                System.out.println("Поток " + (id + 1) + ": (" + from + ".." + (to - 1) + ") сумма = " + sum);
            });
            threads[t].start();
        }

        // join() ждёт потоки и заодно гарантирует видимость записей в sums[]
        // главному потоку, поэтому дополнительная синхронизация не требуется.
        for (Thread thread : threads) {
            thread.join();
        }

        long total = 0;
        for (long sum : sums) {
            total += sum;
        }

        System.out.println("Общая сумма: " + total);
    }
}
