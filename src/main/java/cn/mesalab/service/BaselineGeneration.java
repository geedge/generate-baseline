package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.dao.ReadHistoricalDruidData;
import cn.mesalab.utils.DruidUtils;
import cn.mesalab.utils.HbaseUtils;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.vavr.Tuple2;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
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

    private static final AvaticaConnection druidConn = DruidUtils.getConn();
    private static AvaticaStatement druidStatement;

    static {
        try {
            druidStatement = DruidUtils.getStatement(druidConn);
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }

    private static final Table hbaseTable = HbaseUtils.getInstance().getHbaseTable();

    private static final List<String> ATTACK_TYPE_LIST = Arrays.asList(
            ApplicationConfig.DRUID_ATTACKTYPE_TCP_SYN_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_ICMP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_UDP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_DNS_AMPL
    );
    private static final Integer BASELINE_POINT_NUM =
            ApplicationConfig.BASELINE_RANGE_DAYS * 24 * (60/ApplicationConfig.HISTORICAL_GRAD);

    private static final Tuple2<Long, Long> START_END_TIMES = DruidData.getTimeLimit();
    private static final String TIME_FILTER = ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " >= MILLIS_TO_TIMESTAMP(" + START_END_TIMES._2
            + ") AND " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " < MILLIS_TO_TIMESTAMP(" + START_END_TIMES._1 + ")";

    private static final ArrayList<Map<String, Object>> allFromDruid = new ArrayList<>();

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

            druidConn.close();
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
        ArrayList<Future<ArrayList<Map<String, Object>>>> resultList = new ArrayList<>();
        for (int i = 0; i < (START_END_TIMES._1-START_END_TIMES._2)/timeGrad; i++) {
            String sql = DruidData.getDruidQuerySql(START_END_TIMES._1, i, timeGrad);
            ReadHistoricalDruidData readHistoricalDruidData = new ReadHistoricalDruidData(
                    sql,
                    druidStatement
            );
            Future<ArrayList<Map<String, Object>>> future = loadDataExecutor.submit(readHistoricalDruidData);
            resultList.add(future);
        }
        for(Future<ArrayList<Map<String, Object>>> future: resultList){
            try {
                if(future.get()!=null){
                    allFromDruid.addAll(future.get());
                }else{
                    LOG.error("future.get()未获取到结果");
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        long last = System.currentTimeMillis();
        LOG.info("Druid 加载数据共耗时："+(last-start));
        loadDataExecutor.shutdown();
        loadDataExecutor.awaitTermination(10L, TimeUnit.HOURS);


        // BaseLine生成
        // 获取IP列表
        List<String> destinationIps = DruidData.getServerIpList(allFromDruid);
        ThreadFactory generationThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("baseline-generate-%d").build();
        ThreadPoolExecutor generationExecutor = new ThreadPoolExecutor(
                threadNum, threadNum, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1024), generationThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());

        LOG.info("共查询到服务端ip " +destinationIps.size() + " 个");
        LOG.info("Baseline batch 大小: " + ApplicationConfig.BASELINE_GENERATE_BATCH_SIZE);

        // 分批进行IP baseline生成和处理
        List<List<String>> batchIpLists = Lists.partition(destinationIps, ApplicationConfig.BASELINE_GENERATE_BATCH_SIZE);

        for (List<String> batchIps: batchIpLists){
            if(batchIps.size()>0){
                BaselineSingleThread baselineSingleThread = new BaselineSingleThread(
                        batchIps,
                        druidConn,
                        druidStatement,
                        hbaseTable,
                        ATTACK_TYPE_LIST,
                        BASELINE_POINT_NUM,
                        TIME_FILTER,
                        allFromDruid
                );
                generationExecutor.execute(baselineSingleThread);
            }
        }

        generationExecutor.shutdown();
        generationExecutor.awaitTermination(10L, TimeUnit.HOURS);
    }

}
