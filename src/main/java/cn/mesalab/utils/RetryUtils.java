package cn.mesalab.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/8/9 3:59 下午
 */
public abstract class RetryUtils {
    private static final Logger LOG = LoggerFactory.getLogger(RetryUtils.class);

    private static final int DEFAULT_RETRY_TIME = 1;
    private int retryTime = DEFAULT_RETRY_TIME;
    // 默认睡眠时间1s
    private int sleepTime = 1000;

    public int getSleepTime() {
        return sleepTime;
    }

    public RetryUtils setSleepTime(int sleepTime) {
        if(sleepTime < 0) {
            throw new IllegalArgumentException("sleepTime should equal or bigger than 0");
        }
        this.sleepTime = sleepTime;
        return this;
    }

    public int getRetryTime() {
        return retryTime;
    }

    public RetryUtils setRetryTime(int retryTime) {
        if (retryTime <= 0) {
            throw new IllegalArgumentException("retryTime should bigger than 0");
        }
        this.retryTime = retryTime;
        return this;
    }

    /**
     * 重试的业务执行代码
     * 失败时请抛出一个异常
     * 预留方法：需要重试的业务代码，然后执行
     * @return
     */
    protected abstract Object toTry() throws Exception;

    public Object execute() throws InterruptedException {
        for (int i = 0; i < retryTime; i++) {
            try {
                return toTry();
            } catch (Exception e) {
                // LOG.error("Catched Too-Many-Connections Error, 等待重连");
                Thread.sleep(sleepTime);
            }
        }
        return null;
    }

    public Object submit(ExecutorService executorService) {
        if (executorService == null) {
            throw new IllegalArgumentException("please choose executorService!");
        }
        return executorService.submit((Callable) this::execute);
    }
}