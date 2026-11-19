/**
 * Практическая работа №2, задание №2.
 *
 * Массив из 100 чисел (1..100) обрабатывается четырьмя потоками: каждый считает сумму
 * своего участка по 25 элементов и печатает её. Главный поток дожидается всех и выводит
 * общую сумму, полученную объединением частичных результатов.
 *
 * В потоковой обработке нет ни одного if — только цикл и сложение.
 */
public final class ArraySum {

    private static final int SIZE = 100;
    private static final int THREADS = 4;
    private static final int CHUNK = SIZE / THREADS; // 25 элементов на поток

    private ArraySum() {
    }

    public static void main(String[] args) throws InterruptedException {
        int[] data = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            data[i] = i + 1; // 1..100
        }

        long[] partial = new long[THREADS];
        Thread[] workers = new Thread[THREADS];

        for (int t = 0; t < THREADS; t++) {
            final int index = t;
            final int from = index * CHUNK;        // 0, 25, 50, 75
            final int to = from + CHUNK;           // 25, 50, 75, 100

            workers[t] = Thread.ofPlatform()
                    .name("summer-" + (index + 1))
                    .start(() -> {
                        long sum = 0L;
                        for (int i = from; i < to; i++) {
                            sum += data[i];
                        }
                        partial[index] = sum;
                        System.out.printf("Поток %d: индексы %d..%d, сумма = %d%n",
                                index + 1, from, to - 1, sum);
                    });
        }

        // join() не только дожидается потоков, но и даёт happens-before:
        // записи в partial[] из потоков видны главному потоку без дополнительной синхронизации.
        for (Thread worker : workers) {
            worker.join();
        }

        long total = 0L;
        for (long part : partial) {
            total += part;
        }

        long check = (long) SIZE * (SIZE + 1) / 2; // контроль: 1 + 2 + ... + 100
        System.out.println("Общая сумма: " + total);
        System.out.println("Проверка по формуле: " + check + (total == check ? " — совпадает" : " — РАСХОЖДЕНИЕ"));
    }
}
