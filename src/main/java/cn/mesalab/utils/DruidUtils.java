package cn.mesalab.utils;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.service.BaselineGeneration;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.jfree.util.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/23 4:50 下午
 */
public class DruidUtils {
    private static final Logger LOG = LoggerFactory.getLogger(DruidUtils.class);

    private static DruidUtils druidUtils;
    private static final String DRUID_URL = ApplicationConfig.DRUID_URL;
    private static AvaticaConnection connection;
    private static AvaticaStatement statement;

    public DruidUtils() throws SQLException {
        Properties properties = new Properties();
        connection = (AvaticaConnection) DriverManager.getConnection(DRUID_URL, properties);
        statement = connection.createStatement();
    }

    public static DruidUtils getInstance() {
        if (connection==null){
            try{
                druidUtils = new DruidUtils();
            } catch (SQLException e){
                LOG.error("Druid 建立连接失败！");
                e.printStackTrace();
            }
        }
        return druidUtils;
    }

    private static AvaticaConnection getConn() throws SQLException {
        Properties properties = new Properties();
        AvaticaConnection connection = (AvaticaConnection) DriverManager.getConnection(DRUID_URL, properties);
        return connection;
    }

    public void closeConnection() {
        if(connection != null){
            try{
                connection.close();
            } catch (SQLException e){
                LOG.error("Druid 关闭连接失败！");
                e.printStackTrace();
            }
        }
    }

    public ResultSet executeQuery (String sql) throws SQLException{
        System.out.println("executeQuery:"+sql);
        ResultSet resultSet = statement.executeQuery(sql);
        return resultSet;
    }

    public AvaticaConnection getConnection() {
        return connection;
    }

    public AvaticaStatement getStatement() {
        return statement;
    }
}
