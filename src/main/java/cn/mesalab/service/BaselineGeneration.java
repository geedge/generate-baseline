package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.dao.ReadHistoricalDruidData;
import cn.mesalab.utils.HbaseUtils;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.vavr.Tuple2;
import org.apache.commons.collections.ListUtils;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * @author yjy
 * @version 1.0
 * baseline生成及写入
 * @date 2021/7/23 5:38 下午
 */
public class BaselineGeneration {
    private static final Logger LOG = LoggerFactory.getLogger(BaselineGeneration.class);

    private static final List<String> ATTACK_TYPE_LIST = Arrays.asList(
            ApplicationConfig.DRUID_ATTACKTYPE_TCP_SYN_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_ICMP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_UDP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_DNS_AMPL
            );
    private static final Integer BASELINE_POINT_NUM =
            ApplicationConfig.BASELINE_RANGE_DAYS * 24 * (60/ApplicationConfig.HISTORICAL_GRAD);

    private static final Tuple2<Long, Long> START_END_TIMES = DruidData.getTimeLimit();
    private static final int threadPoolNum = ApplicationConfig.THREAD_POOL_NUM;
    // 每个线程读取数据所覆盖的partition_num个数
    private static final int batchPartitionRange = (int) Math.ceil(ApplicationConfig.PARTITION_NUM_MAX /(double)threadPoolNum);

    /**
     * 程序执行
     */
    public static void perform() {
        long start = System.currentTimeMillis();

        try{
            baselineGeneration();
        } catch (Exception e){
            e.printStackTrace();
        } finally {
            long last = System.currentTimeMillis();
            LOG.warn("运行时间:" + (last - start));
        }
        System.exit(0);
    }

    private static void baselineGeneration() throws InterruptedException {
        CountDownLatch generateCountDownLatch = new CountDownLatch(threadPoolNum);

        ThreadFactory generationThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("baseline-generate-%d").build();
        ThreadPoolExecutor generationExecutor = new ThreadPoolExecutor(
                threadPoolNum, threadPoolNum, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1024), generationThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());

        for(int threadCount = 0; threadCount<threadPoolNum; threadCount++){
            BaselineSingleThread baselineSingleThread = new BaselineSingleThread(
                    ATTACK_TYPE_LIST,
                    BASELINE_POINT_NUM,
                    batchPartitionRange,
                    threadCount,
                    generateCountDownLatch
            );
            generationExecutor.execute(baselineSingleThread);
        }
        generationExecutor.shutdown();
        generateCountDownLatch.await();
    }
}
