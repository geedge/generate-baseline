package cn.mesalab.config;

import cn.mesalab.utils.ConfigUtils;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/24 10:23 上午
 */
public class ApplicationConfig {

    public static final String DRUID_URL= ConfigUtils.getStringProperty("druid.url");
    public static final String DRUID_DRIVER = ConfigUtils.getStringProperty("druid.driver");
    public static final String DRUID_TABLE = ConfigUtils.getStringProperty("druid.table");


    public static final Integer DRUID_TIME_LIMIT_TYPE = ConfigUtils.getIntProperty("read.druid.time.limit.type");
    public static final Long READ_DRUID_MAX_TIME = ConfigUtils.getLongProperty("read.druid.max.time");
    public static final Long READ_DRUID_MIN_TIME = ConfigUtils.getLongProperty("read.druid.min.time");

    public static final Integer READ_HISTORICAL_DAYS = ConfigUtils.getIntProperty("read.historical.days");
    public static final Integer HISTORICAL_GRAD = ConfigUtils.getIntProperty("historical.grad");
    public static final String TIME_FORMAT = ConfigUtils.getStringProperty("time.format");
    public static final String BASELINE_METRIC_TYPE = ConfigUtils.getStringProperty("baseline.metric.type");

    public static final String DRUID_ATTACKTYPE_TCP_SYN_FLOOD = ConfigUtils.getStringProperty("druid.attacktype.tcpsynflood");
    public static final String DRUID_ATTACKTYPE_UDP_FLOOD = ConfigUtils.getStringProperty("druid.attacktype.udpflood");
    public static final String DRUID_ATTACKTYPE_ICMP_FLOOD = ConfigUtils.getStringProperty("druid.attacktype.icmpflood");
    public static final String DRUID_ATTACKTYPE_DNS_AMPL = ConfigUtils.getStringProperty("druid.attacktype.dnsamplification");
    public static final String DRUID_SERVERIP_COLUMN_NAME = ConfigUtils.getStringProperty("druid.serverip.columnname");
    public static final String DRUID_ATTACKTYPE_COLUMN_NAME = ConfigUtils.getStringProperty("druid.attacktype.columnname");
    public static final String DRUID_RECVTIME_COLUMN_NAME = ConfigUtils.getStringProperty("druid.recvtime.columnname");

    public static final Float BASELINE_PERIOD_CORR_THRE = ConfigUtils.getFloatProperty("baseline.period.correlative.threshold");
    public static final Float BASELINE_HISTORICAL_RATIO = ConfigUtils.getFloatProperty("baseline.historical.ratio.threshold");
    public static final Float BASELINE_SPARSE_FILL_PERCENTILE = ConfigUtils.getFloatProperty("baseline.historical.sparse.fill.percentile");
    public static final String BASELINE_FUNCTION = ConfigUtils.getStringProperty("baseline.function");
    public static final Integer BASELINE_RANGE_DAYS = ConfigUtils.getIntProperty("baseline.range.days");
    public static final Float BASELINE_RATIONAL_PERCENTILE = ConfigUtils.getFloatProperty("baseline.rational.percentile");


    public static final String HBASE_TABLE = ConfigUtils.getStringProperty("hbase.table");
    public static final String HBASE_ZOOKEEPER_QUORUM= ConfigUtils.getStringProperty("hbase.zookeeper.quorum");
    public static final String HBASE_ZOOKEEPER_CLIENT_PORT= ConfigUtils.getStringProperty("hbase.zookeeper.client.port");


    public static final Double BASELINE_KALMAN_P = ConfigUtils.getDoubleProperty("baseline.kalman.p");
    public static final Double BASELINE_KALMAN_R = ConfigUtils.getDoubleProperty("baseline.kalman.r");

    public static final Integer BASELINE_GENERATE_BATCH_SIZE = ConfigUtils.getIntProperty("baseline.generate.batch.size");
    public static final Long DRUID_READ_BATCH_TIME_GRAD_HOUR = ConfigUtils.getLongProperty("druid.read.batch.time.grad.hour");
    public static final Integer THREAD_POOL_NUM = ConfigUtils.getIntProperty("thread.pool.num");

    // http config
    public static final Integer HTTP_REQUEST_TIMEOUT = ConfigUtils.getIntProperty("http.request.timeout");
    public static final Integer HTTP_RESPONSE_TIMEOUT = ConfigUtils.getIntProperty("http.response.timeout");
    public static final Integer HTTP_CONNECTION_TIMEOUT = ConfigUtils.getIntProperty("http.connection.timeout");
    public static final Integer HTTP_MAX_CONNECTION_NUM = ConfigUtils.getIntProperty("http.max.connection.num");
    public static final Integer HTTP_MAX_PER_ROUTE = ConfigUtils.getIntProperty("http.max.per.route");

}

