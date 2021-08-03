package cn.mesalab.dao;

import org.apache.calcite.avatica.AvaticaStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/8/3 8:10 下午
 */
public class ReadHistoricalDruidData implements Callable {
    private static final Logger LOG = LoggerFactory.getLogger(ReadHistoricalDruidData.class);

    private String sql;
    private AvaticaStatement statement;

    public ReadHistoricalDruidData(
            String sql,
            AvaticaStatement statement
    ){
        this.sql = sql;
        this.statement = statement;
    }

    @Override
    public ArrayList<Map<String, Object>> call() {
        ArrayList<Map<String, Object>> resultData = new ArrayList<>();
        try {
            long start = System.currentTimeMillis();

            resultData.addAll(DruidData.readFromDruid(sql, statement));
            long end = System.currentTimeMillis();
            LOG.info(sql + "\n读取" + resultData.size() + "条数据，运行时间：" + (end - start));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return resultData;
    }

}
