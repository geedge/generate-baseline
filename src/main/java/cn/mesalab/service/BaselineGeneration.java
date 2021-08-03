package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.utils.DruidUtils;
import cn.mesalab.utils.HbaseUtils;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
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

    private static AvaticaConnection druidConn = DruidUtils.getConn();
    private static AvaticaStatement druidStatement;

    static {
        try {
            druidStatement = DruidUtils.getStatement(druidConn);
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }

    private static Table hbaseTable = HbaseUtils.getInstance().getHbaseTable();

    private static List<String> attackTypeList = Arrays.asList(
            ApplicationConfig.DRUID_ATTACKTYPE_TCP_SYN_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_ICMP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_UDP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_DNS_AMPL
    );
    private static final Integer BASELINE_POINT_NUM =
            ApplicationConfig.BASELINE_RANGE_DAYS * 24 * (60/ApplicationConfig.HISTORICAL_GRAD);

    private static String timeFilter = ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " >= MILLIS_TO_TIMESTAMP(" + DruidData.getTimeLimit()._2
            + ") AND " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " < MILLIS_TO_TIMESTAMP(" + DruidData.getTimeLimit()._1 + ")";

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
        int threadNum = Runtime.getRuntime().availableProcessors();

        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("baseline-demo-%d").build();

        // 创建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threadNum,
                threadNum,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1024),
                namedThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());

        // IP列表获取
        ArrayList<String> destinationIps = DruidData.getServerIpList(druidStatement, timeFilter);

        LOG.info("共查询到服务端ip " +destinationIps.size() + " 个");
        LOG.info("Baseline batch 大小: " + ApplicationConfig.GENERATE_BATCH_SIZE);

        // 分批进行IP baseline生成和处理
        List<List<String>> batchIpLists = Lists.partition(destinationIps, ApplicationConfig.GENERATE_BATCH_SIZE);

        for (List<String> batchIps: batchIpLists){
            if(batchIps.size()>0){
                BaselineSingleThread testForInsider = new BaselineSingleThread(
                        batchIps,
                        druidConn,
                        druidStatement,
                        hbaseTable,
                        attackTypeList,
                        BASELINE_POINT_NUM,
                        timeFilter
                );
                executor.execute(testForInsider);
            }
        }

        executor.shutdown();
        executor.awaitTermination(10L, TimeUnit.HOURS);
    }

}
