package cn.mesalab.dao;

import cn.mesalab.utils.DruidUtils;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/8/3 8:10 下午
 */
public class ReadHistoricalDruidData implements Callable<Map<String, List<Map<String, Object>>>> {
    private static final Logger LOG = LoggerFactory.getLogger(ReadHistoricalDruidData.class);

    private final String sql;
    private final CountDownLatch countDownLatch;

    public ReadHistoricalDruidData(
            String sql,
            CountDownLatch countDownLatch
    ){
        this.sql = sql;
        this.countDownLatch = countDownLatch;
    }

    @Override
    public Map<String, List<Map<String, Object>>> call() {
        Map<String, List<Map<String, Object>>> resultData = new HashMap<>();
        try {
            long start = System.currentTimeMillis();
            AvaticaConnection connection = DruidUtils.getConn();
            AvaticaStatement stat = connection.createStatement();
            Map<String, List<Map<String, Object>>> readFromDruid = DruidData.readFromDruid(sql, stat);
            resultData.putAll(readFromDruid);

            long end = System.currentTimeMillis();
            LOG.info(sql + "\n读取" + resultData.size() + "条数据，运行时间：" + (end - start));
            connection.close();
            stat.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            countDownLatch.countDown();
            LOG.info("本线程读取完毕，剩余线程数量：" + countDownLatch.getCount());
        }
        return resultData;
    }

}
