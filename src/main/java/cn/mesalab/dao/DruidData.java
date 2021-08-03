package cn.mesalab.dao;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.Impl.ResultSetToListServiceImp;
import cn.mesalab.utils.DruidUtils;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static DruidData druidData;
    private AvaticaConnection connection;
    private AvaticaStatement statement;


    {
        connectionInit();
    }

    /**
     * 连接初始化
     */
    private void connectionInit(){
        try {
            connection = DruidUtils.getConn();
            statement = connection.createStatement();
            statement.setQueryTimeout(0);

        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }

    /**
     * 获取实例
     * @return DruidData实例
     */
    public static DruidData getInstance() {
        druidData = new DruidData();
        return druidData;
    }

    /**
     * 获取distinct server ip
     * @return ArrayList<String> ip列表
     */
    public static ArrayList<String> getServerIpList(AvaticaStatement statement, String timeFilter) {
        Long startQueryIpLIstTime = System.currentTimeMillis();
        ArrayList<String> serverIps = new ArrayList<String>();
        String sql = "SELECT distinct " + ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + " FROM " + ApplicationConfig.DRUID_TABLE
                + " WHERE " + timeFilter
                + " LIMIT 200";// FOR TEST
        try{
            ResultSet resultSet = DruidUtils.executeQuery(statement,sql);
            while(resultSet.next()){
                String ip = resultSet.getString(ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME);
                serverIps.add(ip);
            }
        } catch (Exception e){
            e.printStackTrace();
        }
        Long endQueryIpListTime = System.currentTimeMillis();
        LOG.info("性能测试：ip list查询耗时——"+(endQueryIpListTime-startQueryIpLIstTime));

        return serverIps;
    }

    public static List<String> getServerIpList(List<Map<String, Object>> dataFromDruid) {
        List<String> serverIps = new ArrayList<>();
        List<String> collect = dataFromDruid.stream().map(i -> i.get(ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME).toString())
                .collect(Collectors.toList());
        serverIps = collect.stream().distinct().collect(Collectors.toList());
        return serverIps;
    }

    /**
     * 从Druid读取目标IP相关数据
     * @param ipList ip列表
     * @return 数据库读取结果
     */
    public static List<Map<String, Object>> readFromDruid(AvaticaConnection connection,  AvaticaStatement statement, List<String> ipList, String timeFilter){
        List<Map<String, Object>> rsList = null;
        ipList = ipList.stream().map( ip -> "\'"+ip+"\'").collect(Collectors.toList());
        String ipString = "(" + StringUtils.join(ipList, ",").toString() + ")";
        String sql = "SELECT "+ ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + ", "+ ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME
                + ", "+ ApplicationConfig.BASELINE_METRIC_TYPE
                + ", " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " FROM " + ApplicationConfig.DRUID_TABLE
                + " WHERE " + ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + " IN " + ipString
                + " AND " + timeFilter;
        try{
            ResultSet resultSet = DruidUtils.executeQuery(statement, sql);
            ResultSetToListService service  = new ResultSetToListServiceImp();
            rsList = service.selectAll(resultSet);
        } catch (Exception e){
            e.printStackTrace();
        }
        return rsList;
    }

    public static List<Map<String, Object>> readFromDruid(String sql, AvaticaStatement statement){
        List<Map<String, Object>> rsList = null;
        try{
            ResultSet resultSet = DruidUtils.executeQuery(statement, sql);
            ResultSetToListService service  = new ResultSetToListServiceImp();
            rsList = service.selectAll(resultSet);
        } catch (Exception e){
            e.printStackTrace();
        }
        return rsList;
    }

    public static List<Map<String, Object>> getBatchData(List<Map<String, Object>>allData, List<String> ipList){
        ArrayList<Map<String, Object>> rsList = new ArrayList<>();
        for(Map<String, Object> record: allData){
            if(ipList.contains(record.get(ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME))){
                rsList.add(record);
            }
        }
        return rsList;
    }


    /**
     * 从数据库读取结果中筛选指定ip的指定攻击类型的数据
     * @param allData 数据库读取结果
     * @param ip 指定ip
     * @param attackType 指定攻击类型
     * @return 筛选结果
     */
    public static List<Map<String, Object>> getTimeSeriesData(List<Map<String, Object>> allData, String ip, String attackType){
        List<Map<String, Object>> rsList = new ArrayList<>();
        try{
            rsList = allData.stream().
                    filter(i->((i.get(ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME).equals(ip))
                            )&&(i.get(ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME).equals(attackType)))
                    .collect(Collectors.toList());
        } catch (NullPointerException e){
        }
        return rsList;
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
        return Tuple.of(maxTime, minTime);
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

    /**
     * 关闭当前DruidData
     */
    public void closeConn(){
        try {
            DruidUtils.closeConnection();
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }

    public static String getDruidQuerySql(Long originBeginTime, int currentPart, long timeGrad){
        long startTime = originBeginTime +  currentPart * timeGrad;
        long endTime = originBeginTime +  (currentPart+1) * timeGrad;
        String timeFilter = ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " >= MILLIS_TO_TIMESTAMP(" + startTime
                + ") AND " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " < MILLIS_TO_TIMESTAMP(" + endTime + ")";

        String sql = "SELECT "+ ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + ", "+ ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME
                + ", "+ ApplicationConfig.BASELINE_METRIC_TYPE
                + ", " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
                + " FROM " + ApplicationConfig.DRUID_TABLE
                + " WHERE " + timeFilter; // FOR TEST
        return sql;
    }

}
