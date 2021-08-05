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

    private static final Table hbaseTable = HbaseUtils.getInstance().getHbaseTable();

    private static final List<String> ATTACK_TYPE_LIST = Arrays.asList(
            ApplicationConfig.DRUID_ATTACKTYPE_TCP_SYN_FLOOD
//            ApplicationConfig.DRUID_ATTACKTYPE_ICMP_FLOOD,
//            ApplicationConfig.DRUID_ATTACKTYPE_UDP_FLOOD,
//            ApplicationConfig.DRUID_ATTACKTYPE_DNS_AMPL
            );
    private static final Integer BASELINE_POINT_NUM =
            ApplicationConfig.BASELINE_RANGE_DAYS * 24 * (60/ApplicationConfig.HISTORICAL_GRAD);

    private static final Tuple2<Long, Long> START_END_TIMES = DruidData.getTimeLimit();
    private static final Map<String, List<Map<String, Object>>> allFromDruid = new HashMap<>();

    /**
     * 程序执行
     */
    public void perform() {
        long start = System.currentTimeMillis();

        try{
            // baseline生成并写入
            generateBaselinesThread();

            long last = System.currentTimeMillis();
            LOG.warn("运行时间:" + (last - start));

            hbaseTable.close();
            LOG.info("Druid 关闭连接");

        } catch (Exception e){
            e.printStackTrace();
        }
        System.exit(0);
    }

    /**
     * 多线程baseline生成入口
     * @throws InterruptedException
     */
    private void generateBaselinesThread() throws InterruptedException {
        int threadNum = ApplicationConfig.THREAD_MAX_NUM;

        // 数据读取
        LOG.info("Druid 开始读取数据");
        long start = System.currentTimeMillis();
        ThreadFactory loadDataThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("baseline-load-data-%d").build();
        ThreadPoolExecutor loadDataExecutor = new ThreadPoolExecutor(
                threadNum, threadNum, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1024), loadDataThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        long timeGrad = 3600000 * ApplicationConfig.DRUID_READ_BATCH_TIME_GRAD_HOUR;
        int threadPoolNum = (int) ((START_END_TIMES._2-START_END_TIMES._1)/timeGrad);
        ArrayList<Future<Map<String, List<Map<String, Object>>>>> resultList = new ArrayList<>();
        CountDownLatch loadDataCountDownLatch = new CountDownLatch(threadPoolNum);
        for (int i = 0; i < threadPoolNum; i++) {
            String sql = DruidData.getDruidQuerySql(ATTACK_TYPE_LIST, START_END_TIMES._1, i, timeGrad);
            ReadHistoricalDruidData readHistoricalDruidData = new ReadHistoricalDruidData(
                    sql,
                    loadDataCountDownLatch
            );
            Future<Map<String, List<Map<String, Object>>>> future = loadDataExecutor.submit(readHistoricalDruidData);
            resultList.add(future);
        }
        loadDataExecutor.shutdown();
        loadDataCountDownLatch.await();

        for(Future<Map<String, List<Map<String, Object>>>> future: resultList){
            try {
                Map<String, List<Map<String, Object>>> queryBatchIpData = future.get();
                if(queryBatchIpData !=null){
                    queryBatchIpData.forEach((ip, data)->
                            allFromDruid.merge(ip, data, ListUtils::union));
                }else{
                    LOG.error("future.get()未获取到结果");
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
        long last = System.currentTimeMillis();
        LOG.info("Druid 加载数据共耗时："+(last-start));

        // BaseLine生成
        ThreadFactory generationThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("baseline-generate-%d").build();
        ThreadPoolExecutor generationExecutor = new ThreadPoolExecutor(
                threadNum, threadNum, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1024), generationThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());

        LOG.info("共查询到服务端ip " +allFromDruid.size() + " 个");
        LOG.info("Baseline batch 大小: " + ApplicationConfig.BASELINE_GENERATE_BATCH_SIZE);


        List<Map<String, List<Map<String, Object>>>> batchDruidDataLists = DruidData.splitMap(allFromDruid, ApplicationConfig.BASELINE_GENERATE_BATCH_SIZE);
        CountDownLatch generateCountDownLatch = new CountDownLatch(batchDruidDataLists.size());
        for (Map<String, List<Map<String, Object>>>batchDruidData: batchDruidDataLists){
            if(batchDruidData.size()>0){
                BaselineSingleThread baselineSingleThread = new BaselineSingleThread(
                        hbaseTable,
                        ATTACK_TYPE_LIST,
                        BASELINE_POINT_NUM,
                        batchDruidData,
                        generateCountDownLatch
                );
                generationExecutor.execute(baselineSingleThread);
            }
        }
        generationExecutor.shutdown();
        generateCountDownLatch.await();
    }

}
