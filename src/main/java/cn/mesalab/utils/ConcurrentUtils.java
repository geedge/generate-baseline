package cn.mesalab.utils;

import com.google.common.collect.Lists;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 〈一句话功能简述〉:
 * 〈并发工具〉
 *
 * @create 2020-05-28
 * @since 1.0.0
 */
public class ConcurrentUtils {
    /**
     * @Description
     * 并发执行工具类
     *
     * @param batchProcessNum 并发执行的个数
     * @param executor 执行需要的线程池
     * @param keys 批量执行的key
     * @param kvConvert 从key生成value
     * @param valueProcessor 对value的后置处理
     * @param exceptionHook 通过key获取value时发生异常自定义处理
     * @return void
     **/
    public static <K, V> void concurrentProcess(int batchProcessNum, AsyncTaskExecutor executor, K[] keys,
                                                Function<K, V> kvConvert,
                                                ValueProcessor<K, V> valueProcessor,
                                                ExceptionHook<K> exceptionHook ){
        AtomicInteger index = new AtomicInteger(1);
        List<Future> futureList = Lists.newArrayListWithExpectedSize(batchProcessNum);
        final int length = keys.length;
        System.out.println("total jobs size:{}" + length);
        for (int i = 0; i < batchProcessNum; i++) {
            int finalI = i;
            futureList.add(executor.submit(() -> {
                System.out.println("batch process start thread num:{}"+ finalI);
                int currentIndex;
                while (length >= (currentIndex = index.getAndIncrement())) {
                    System.out.println("current job index:"+currentIndex+" of "+ length);
                    K key = keys[currentIndex-1];
                    try {
                        valueProcessor.process(key, kvConvert.apply(key));
                    } catch (Exception e) {
                        exceptionHook.process(key, e);
                    }
                }
                System.out.println("batch process end thread num:{}"+finalI);
            }));
        }
        waitFutureFinished(futureList);
    }

    public static <K, V> void concurrentProcess(int batchProcessNum, AsyncTaskExecutor executor, K[] keys,
                                                Function<K, V> kvConvert,
                                                ValueProcessor<K, V> valueProcessor) {
        concurrentProcess(batchProcessNum, executor, keys, kvConvert, valueProcessor,
                (key,e) -> System.out.println("通过key:"+ key +"  获取value异常, e:"+e));
    }

    /**
     * @Description
     * 并发执行工具类
     *
     * @param batchProcessNum 并发执行的个数
     * @param executor 执行需要的线程池
     * @param keys 批量执行的key
     * @param kvConvert 从key生成value
     * @return Collection<V> 返回value的集合
     **/
    public static <K, V> Collection<V> concurrentGet(int batchProcessNum, AsyncTaskExecutor executor, K[] keys,
                                                     Function<K, V> kvConvert) {
        List<V> rt = Lists.newArrayListWithCapacity(keys.length);
        concurrentProcess(batchProcessNum, executor, keys, kvConvert, (k, v) -> {
            if (v == null) {
                System.out.println("key:{} apply value is null");
                return;
            }
            rt.add(v);
        });
        return rt;
    }

    public static void waitFutureFinished(List<Future> unfinishedFuture, boolean ignoreExcetion) {
        boolean interrupt = false;
        while (!unfinishedFuture.isEmpty()) {
            Iterator<Future> iterator = unfinishedFuture.iterator();
            while (iterator.hasNext()) {
                Future next = iterator.next();
                if (next.isDone()) {
                    try {
                        next.get();
                    } catch (InterruptedException | ExecutionException e) {
                        System.out.println("执行异常， e:"+e);
                        if (!ignoreExcetion) {
                            interrupt = true;
                            break;
                        }
                    }
                    iterator.remove();
                }
            }
            if (interrupt) {
                break;
            }
            sleep();
        }
        if (interrupt) {
            Iterator<Future> iterator = unfinishedFuture.iterator();
            while (iterator.hasNext()) {
                Future next = iterator.next();
                if (next.isDone()) {
                    next.cancel(true);
                }
            }
            throw new RuntimeException("任务异常终止");
        }
    }

    public static void waitFutureFinished(List<Future> unfinishedFuture) {
        waitFutureFinished(unfinishedFuture, false);
    }

    public static void sleep() {
        sleep(5000);
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            System.out.println("sleep error, e:" + e);
        }
    }

    @FunctionalInterface
    public interface ValueProcessor<K, V> {
        void process(K key, V value);
    }

    @FunctionalInterface
    public interface ExceptionHook<K>{
        void process(K key, Exception e);
    }
}

