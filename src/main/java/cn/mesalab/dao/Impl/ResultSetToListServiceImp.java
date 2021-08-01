package cn.mesalab.dao.Impl;

import cn.mesalab.dao.ResultSetToListService;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/24 4:29 下午
 */
public class ResultSetToListServiceImp implements ResultSetToListService {

    /**
     * SELECT 查询记录以List结构返回，每一个元素是一条记录
     * 每条记录保存在Map<String, Object>里面，String类型指字段名字，Object对应字段值
     *
     * @param rs
     * @return List<Map<String, Object>>
     */
    @Override
    public List<Map<String, Object>> selectAll(ResultSet rs) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSetMetaData rmd = rs.getMetaData();
            int columnCount = rmd.getColumnCount();
            while (rs.next()) {
                Map<String, Object> rowData = new HashMap<String, Object>();
                for (int i = 1; i <= columnCount; ++i) {
                    rowData.put(rmd.getColumnName(i), rs.getObject(i));
                }
                list.add(rowData);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return list;
    }
}