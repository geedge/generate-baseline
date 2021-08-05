package cn.mesalab.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.omg.PortableInterceptor.INACTIVE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

public class ExecutorThreadPool {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutorThreadPool.class);
    private static ExecutorService pool = null ;
    private static ExecutorThreadPool poolExecutor = null;
    private int threadPoolNum;

    static {
        getThreadPool();
    }

    public ExecutorThreadPool(Integer threadPoolNum){
        this.threadPoolNum.
    }

    private static void getThreadPool(){
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("iplearning-application-pool-%d").build();
        pool = new ThreadPoolExecutor(ApplicationConfig.THREAD_POOL_NUMBER, ApplicationConfig.THREAD_POOL_NUMBER,
                0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024), namedThreadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    public static ExecutorThreadPool getInstance(){
        if (null == poolExecutor){
            poolExecutor = new ExecutorThreadPool();
        }
        return poolExecutor;
    }

    public void executor(Runnable command){
        pool.execute(command);
    }

    @Deprecated
    public void awaitThreadTask(){
        try {
            while (!pool.awaitTermination(ApplicationConfig.THREAD_AWAIT_TERMINATION_TIME, TimeUnit.SECONDS)) {
                LOG.warn("线程池没有关闭");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void shutdown(){
        pool.shutdown();
    }

    @Deprecated
    public static Long getThreadNumber(){
        String name = Thread.currentThread().getName();
        String[] split = name.split("-");
        return Long.parseLong(split[3]);
    }



}
