package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.service.BaselineService.KalmanFilter;
import cn.mesalab.utils.HbaseUtils;
import cn.mesalab.utils.SeriesUtils;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/23 5:38 下午
 */
public class BaselineGeneration {
    private static final Logger LOG = LoggerFactory.getLogger(BaselineGeneration.class);

    private static DruidData druidData;
    private static HbaseUtils hbaseUtils;
    private static Table hbaseTable;

    private static List<Map<String, Object>> batchDruidData = new ArrayList<>();

    private static final Integer BASELINE_POINT_NUM = ApplicationConfig.BASELINE_RANGE_DAYS * 24 * (60/ApplicationConfig.HISTORICAL_GRAD);

    public static void perform() {
        long start = System.currentTimeMillis();

        druidData = DruidData.getInstance();
        hbaseUtils = HbaseUtils.getInstance();
        hbaseTable = hbaseUtils.getHbaseTable();

        LOG.info("Druid 成功建立连接");

        try{
            generateBaselinesThread(ApplicationConfig.DRUID_ATTACKTYPE_TCP_SYN_FLOOD);
//            generateBaselines(ApplicationConfig.DRUID_ATTACKTYPE_UDP_FLOOD);
//            generateBaselines(ApplicationConfig.DRUID_ATTACKTYPE_ICMP_FLOOD);
//            generateBaselines(ApplicationConfig.DRUID_ATTACKTYPE_DNS_AMPL);

            long last = System.currentTimeMillis();
            LOG.warn("运行时间:" + (last - start));

            druidData.closeConn();
            hbaseTable.close();
            LOG.info("Druid 关闭连接");

        } catch (Exception e){
            e.printStackTrace();
        }
        System.exit(0);
    }

    private static void generateBaselinesThread(String attackType) throws InterruptedException {
        int threadNum = Runtime.getRuntime().availableProcessors();

        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat(attackType+"-baseline-demo-%d").build();

        // 创建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threadNum,
                threadNum,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1024),
                namedThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());

        // baseline 生成及写入
        ArrayList<String> destinationIps = druidData.getServerIpList(attackType);
        LOG.info("查询到服务端ip共 " +destinationIps.size() + " 个");

        int batchCount = destinationIps.size() / ApplicationConfig.GENERATE_BATCH_NUM;

        for (int batchCurrent = 0; batchCurrent <batchCount; batchCurrent++){
            List<String> batchIps = destinationIps.subList(batchCurrent*ApplicationConfig.GENERATE_BATCH_NUM,
                    (batchCurrent+1)*ApplicationConfig.GENERATE_BATCH_NUM);
            executor.execute(() -> generateBaselines(batchIps, attackType));
        }

        executor.shutdown();
        executor.awaitTermination(10L, TimeUnit.SECONDS);

        LOG.info("BaselineGeneration 完成:" + attackType);
        LOG.info("BaselineGeneration 共写入数据条数：" + destinationIps.size());
    }

    static void generateBaselines(String attackType){
        ArrayList<String> destinationIps = druidData.getServerIpList(attackType);
        generateBaselines(destinationIps, attackType);
        LOG.info("BaselineGeneration 完成:" + attackType);
        LOG.info("BaselineGeneration 共写入数据条数：" + destinationIps.size());
    }

    public static void generateBaselines(List<String> ipList, String attackType){

        Long startGenerateTime = System.currentTimeMillis();

        druidData = DruidData.getInstance();

        batchDruidData = druidData.readFromDruid(ipList);

        List<Put> putList = new ArrayList<>();
        for(String ip: ipList){
            int[] ipBaseline = generateSingleIpBaseline(ip, attackType);
            if (ipBaseline==null){
                break;
            }
            putList = hbaseUtils.cachedInPut(putList, ip, ipBaseline, attackType, ApplicationConfig.BASELINE_METRIC_TYPE);
        }

        Long endGenerateTime = System.currentTimeMillis();

//        LOG.info("性能测试：Baseline生成耗时——"+(endGenerateTime-startGenerateTime));

        try {
            hbaseTable.put(putList);
            LOG.info("HBase 处理数据条数 " + ApplicationConfig.GENERATE_BATCH_NUM);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Long endWriteTime = System.currentTimeMillis();
//        LOG.info("性能测试：Baseline写入耗时——"+(endWriteTime-endGenerateTime));
    }

    private static int[] generateSingleIpBaseline(String ip, String attackType){
        // 查询
        Long startQuerySingleIPTime = System.currentTimeMillis();
        List<Map<String, Object>> originSeries = druidData.getTimeSeriesData(batchDruidData, ip, attackType);

        if (originSeries.size()==0){
            return null;
        }

        Long endQuerySingleIPTime = System.currentTimeMillis();
        //LOG.info("性能测试：单个ip查询耗时——"+(endQuerySingleIPTime-startQuerySingleIPTime));

        // 时间序列缺失值补0
        List<Map<String, Object>> completSeries = SeriesUtils.complementSeries(originSeries);

        int[] baselineArr = new int[completSeries.size()];
        List<Integer>series = completSeries.stream().map(
                i -> Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList());

        // 判断ip出现频率
        if(originSeries.size()/(float)completSeries.size()>ApplicationConfig.BASELINE_HISTORICAL_RATIO){
            // 低频率
            double percentile = StatUtils.percentile(series.stream().mapToDouble(Double::valueOf).toArray(),
                    ApplicationConfig.BASELINE_SPARSE_FILL_PERCENTILE);
            Arrays.fill(baselineArr, (int)percentile);
            baselineArr = baselineFunction(series);

        } else {
            // 判断周期性
            if (SeriesUtils.isPeriod(series)){
                baselineArr = baselineFunction(series);
            } else {
                int ipPercentile = SeriesUtils.percentile(
                        originSeries.stream().map(i ->
                                Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList()),
                        ApplicationConfig.BASELINE_RATIONAL_PERCENTILE);
                Arrays.fill(baselineArr, ipPercentile);
            }
        }

        Long endGenerateSingleIPTime = System.currentTimeMillis();
        //LOG.info("性能测试：单个baseline生成耗时——"+(endGenerateSingleIPTime-endQuerySingleIPTime));

        return baselineArr;
    }

    private static int[] baselineFunction(List<Integer> timeSeries){
        int[] result;
        switch (ApplicationConfig.BASELINE_FUNCTION){
            case "KalmanFilter":
                KalmanFilter kalmanFilter = new KalmanFilter();
                kalmanFilter.forcast(timeSeries, BASELINE_POINT_NUM);
                result = kalmanFilter.getForecastSeries().stream().mapToInt(Integer::valueOf).toArray();
                break;
            default:
                result = timeSeries.subList(0, BASELINE_POINT_NUM).stream().mapToInt(Integer::valueOf).toArray();
        }
        return result;
    }

    public static void main(String[] args) {
        perform();
    }

}
