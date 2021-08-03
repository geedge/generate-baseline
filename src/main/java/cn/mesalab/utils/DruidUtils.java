package cn.mesalab.utils;

import cn.mesalab.config.ApplicationConfig;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.hadoop.hbase.client.Table;

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
    private static ThreadLocal<AvaticaConnection> threadLocal = new ThreadLocal<AvaticaConnection>();

    private static final String DRUID_URL = ApplicationConfig.DRUID_URL;

    /**
     * 打开连接
     * @throws SQLException
     */
    public static AvaticaConnection getConn() {
        Properties properties = new Properties();
        AvaticaConnection connection = null;
        try {
            connection = (AvaticaConnection) DriverManager.getConnection(DRUID_URL, properties);
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
        threadLocal.set(connection);
        return connection;
    }

    /**
     * 关闭连接
     */
    public static void closeConnection() throws SQLException{
        AvaticaConnection conn = threadLocal.get();
        if(conn != null){
            conn.close();
            threadLocal.remove();
        }
    }

    /**
     * 根据sql查询结果
     */
    public static ResultSet executeQuery (AvaticaStatement statement, String sql) throws SQLException{
        ResultSet resultSet = statement.executeQuery(sql);
        return resultSet;
    }

    public static AvaticaStatement getStatement(AvaticaConnection conn) throws SQLException {
        return conn.createStatement();
    }

}
