package cn.mesalab.dao;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.Impl.ResultSetToListServiceImp;
import cn.mesalab.utils.DruidUtils;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.apache.calcite.avatica.AvaticaConnection;
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
 * @date 2021/7/23 4:56 下午
 */
public class DruidData {

    private static final Logger LOG = LoggerFactory.getLogger(DruidData.class);
    private static DruidData druidData;

    private AvaticaConnection connection;

    {
        try {
            connection = DruidUtils.getConn();
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }


    private String timeFilter = ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " >= MILLIS_TO_TIMESTAMP(" + getTimeLimit()._2
            + ") AND " + ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME
            + " < MILLIS_TO_TIMESTAMP(" + getTimeLimit()._1 + ")";



    public static DruidData getInstance() {
        druidData = new DruidData();
        return druidData;
    }

    public ArrayList<String> getServerIpList() {
        Long startQueryIPLIstTime = System.currentTimeMillis();
        ArrayList<String> serverIPs = new ArrayList<String>();
        String sql = "SELECT distinct " + ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME
                + " FROM " + ApplicationConfig.DRUID_TABLE
                + " WHERE " + timeFilter
                + " LIMIT 10000";// FOR TEST
        try{
            ResultSet resultSet = DruidUtils.executeQuery(connection,sql);
            while(resultSet.next()){
                String ip = resultSet.getString(ApplicationConfig.DRUID_SERVERIP_COLUMN_NAME);
                serverIPs.add(ip);
            }
        } catch (Exception e){
            e.printStackTrace();
        }
        Long endQueryIPListTime = System.currentTimeMillis();
        LOG.info("性能测试：ip list查询耗时——"+(endQueryIPListTime-startQueryIPLIstTime));

        return serverIPs;
    }

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
            ResultSet resultSet = DruidUtils.executeQuery(connection,sql);
            ResultSetToListService service  = new ResultSetToListServiceImp();
            rsList = service.selectAll(resultSet);
        } catch (Exception e){
            e.printStackTrace();
        }
        return rsList;
    }

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

    public void closeConn(){
        try {
            DruidUtils.closeConnection();
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }
}
