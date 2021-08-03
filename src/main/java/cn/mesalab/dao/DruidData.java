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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
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
    private String timeFilter = ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " >= MILLIS_TO_TIMESTAMP(" + getTimeLimit()._2
            + ") AND " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " < MILLIS_TO_TIMESTAMP(" + getTimeLimit()._1 + ")";


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
    public ArrayList<String> getServerIpList() {
        Long startQueryIpLIstTime = System.currentTimeMillis();
        ArrayList<String> serverIps = new ArrayList<String>();
        String sql = "SELECT distinct " + ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + " FROM " + ApplicationConfig.DRUID_TABLE
                + " WHERE " + timeFilter
                + " LIMIT 1000";// FOR TEST
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

    /**
     * 从Druid读取目标IP相关数据
     * @param ipList ip列表
     * @return 数据库读取结果
     */
    public List<Map<String, Object>> readFromDruid(List<String> ipList){
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

    /**
     * 从数据库读取结果中筛选指定ip的指定攻击类型的数据
     * @param allData 数据库读取结果
     * @param ip 指定ip
     * @param attackType 指定攻击类型
     * @return 筛选结果
     */
    public List<Map<String, Object>> getTimeSeriesData(List<Map<String, Object>> allData, String ip, String attackType){
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
    public Tuple2<Long, Long> getTimeLimit(){
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

    private long getCurrentDay(int bias) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_YEAR, calendar.get(Calendar.DAY_OF_YEAR) + bias);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long getCurrentDay(){
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
}
