package cn.mesalab.utils;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import com.google.common.collect.Maps;
import com.zdjizhi.utils.JsonMapper;
import sun.net.util.URLUtil;

import java.net.URL;
import java.util.Map;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/8/3 4:43 下午
 */
public class HttpClientUtilsTest {
    private static HttpClientUtils httpClientUtils = new HttpClientUtils();

    public static void main(String[] args) {
        executeHttpPost("select * from top_server_ip_test_log limit 10");
    }

    private static Map<String, String> executeHttpPost(String sql){
        String queryUrl = "http://192.168.44.12:8082/druid/v2/sql";
        DruidQueryParam druidQueryParam = getDruidQueryParam(sql);
        int socketTimeout = ApplicationConfig.HTTP_RESPONSE_TIMEOUT;
        Map<String, String> stringStringMap = httpClientUtils.httpPost(queryUrl, JsonMapper.toJsonString(druidQueryParam), socketTimeout);
        System.out.println(stringStringMap.toString());
        return stringStringMap;
    }

    public static DruidQueryParam getDruidQueryParam(String sql) {
        DruidQueryParam druidQueryParam = new DruidQueryParam();
        druidQueryParam.setQuery(sql);
        druidQueryParam.getContext().put("skipEmptyBuckets", "true");
        druidQueryParam.setResultFormat("object");
        return druidQueryParam;
    }
}


class DruidQueryParam {
    private String query;
    private Map<String, String> context = Maps.newHashMap();
    private String resultFormat;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Map<String, String> getContext() {
        return context;
    }

    public void setContext(Map<String, String> context) {
        this.context = context;
    }

    public String getResultFormat() {
        return resultFormat;
    }

    public void setResultFormat(String resultFormat) {
        this.resultFormat = resultFormat;
    }
}