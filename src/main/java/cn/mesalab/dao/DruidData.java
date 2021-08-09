package cn.mesalab.dao;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.utils.DruidUtils;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.stream.Collectors;


/**
 * @author yjy
 * @version 1.0
 * Druid 数据库操作
 * @date 2021/7/23 4:56 下午
 */
public class DruidData {

    private static final Logger LOG = LoggerFactory.getLogger(DruidData.class);

    public static Map<String, List<Map<String, Object>>> readFromDruid(String sql, AvaticaStatement statement){
        Map<String, List<Map<String, Object>>> rsList = null;
        try{
            ResultSet resultSet = DruidUtils.executeQuery(statement, sql);
            rsList = selectAll(resultSet);
        } catch (Exception e){
            e.printStackTrace();
        }
        return rsList;
    }

    /**
     * 处理Druid读取返回数据为Map<String, List<Map<String, Object>>>形式
     * 外层map key为ip，内层map的key为ip的一条日志
     * @param rs
     * @return
     */
    public static Map<String, List<Map<String, Object>>> selectAll(ResultSet rs) {
        Map<String, List<Map<String, Object>>> allIpDataList = new HashMap<>();
        ArrayList<String> ipList = new ArrayList<>();

        try {
            ResultSetMetaData rmd = rs.getMetaData();
            int columnCount = rmd.getColumnCount();

            while (rs.next()) {
                Map<String, Object> rowData = new HashMap<>();
                for (int i = 1; i <= columnCount; ++i) {
                    rowData.put(rmd.getColumnName(i), rs.getObject(i));
                }

                String ip = (String) rowData.get(ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME);
                if(!ipList.contains(ip)){
                    ipList.add(ip);
                    List<Map<String, Object>> ipData = new ArrayList<>();
                    allIpDataList.put(ip, ipData);
                }
                rowData.remove(ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME);
                allIpDataList.get(ip).add(rowData);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return allIpDataList;
    }

    /**
     * 计算查询时间范围，可指定时间范围（测试）或使用默认配置
     * @return 时间范围起始点和终止点
     */
    public static Tuple2<Long, Long> getTimeLimit(){
        long maxTime = 0L;
        long minTime = 0L;
        switch(ApplicationConfig.DRUID_TIME_LIMIT_TYPE){
            case 0:
                maxTime = getCurrentDay();
                minTime = getCurrentDay(-ApplicationConfig.READ_HISTORICAL_DAYS);
                break;
            case 1:
                maxTime = ApplicationConfig.READ_DRUID_MAX_TIME;
                minTime = ApplicationConfig.READ_DRUID_MIN_TIME;
                break;
            default:
                LOG.warn("没有设置Druid数据读取方式");
        }
        return Tuple.of(minTime, maxTime);
    }

    private static long getCurrentDay(int bias) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_YEAR, calendar.get(Calendar.DAY_OF_YEAR) + bias);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static long getCurrentDay(){
        return getCurrentDay(0);
    }


    public static String getBatchDruidQuerySql(List<String> attackTypeList, Long originBeginTime, int currentPart, long timeGrad){
        long startTime = originBeginTime +  currentPart * timeGrad;
        long endTime = originBeginTime +  (currentPart+1) * timeGrad;
        attackTypeList = attackTypeList.stream().map(attack -> "'"+attack+"'").collect(Collectors.toList());
        String attackList = "(" + StringUtils.join(attackTypeList, ",") + ")";
        String timeFilter = ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " >= MILLIS_TO_TIMESTAMP(" + startTime
                + ") AND " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " < MILLIS_TO_TIMESTAMP(" + endTime + ")";

        return "SELECT "+ ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + ", "+ ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME
                + ", "+ ApplicationConfig.BASELINE_METRIC_TYPE
                + ", " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " FROM " + ApplicationConfig.DRUID_TABLE
                + " WHERE " + ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME
                + " IN " + attackList
                + " AND " + timeFilter;
    }

    public static String getBatchDruidQuerySql(List<String> attackTypeList, int currentPart, int partitionNumGrad){
        int startPartitionNum = currentPart * partitionNumGrad;
        int endPartitionNum = (currentPart + 1) * partitionNumGrad;
        attackTypeList = attackTypeList.stream().map(attack -> "'"+attack+"'").collect(Collectors.toList());
        String attackList = "(" + StringUtils.join(attackTypeList, ",") + ")";
        String partitionFilter = ApplicationConfig.DRUID_COLUMNNAME_PARTITION_NUM
                + " >= " + startPartitionNum
                + " AND " + ApplicationConfig.DRUID_COLUMNNAME_PARTITION_NUM
                + " < " + endPartitionNum;

        return "SELECT "+ ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + ", "+ ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME
                + ", "+ ApplicationConfig.BASELINE_METRIC_TYPE
                + ", " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " FROM " + ApplicationConfig.DRUID_TABLE
                + " WHERE " + ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME
                + " IN " + attackList
                + " AND " + partitionFilter;
    }

    /**
     * 描述：分割Map
     * @param map 原始数据
     * @param pageSize 每个map数量
     * @return ListList<Map<K, V>>
     */
    public static <K, V> List<Map<K, V>> splitMap(Map<K, V> map, int pageSize){
        if(map == null || map.isEmpty()){
            return Collections.emptyList();
        }
        List<Map<K, V>> newList = new ArrayList<>();
        int j = 0;
        for(K k :map.keySet()){
            if(j%pageSize == 0) {
                newList.add(new HashMap<>());
            }
            newList.get(newList.size()-1).put(k, map.get(k));
            j++;
        }
        return newList;
    }
}
