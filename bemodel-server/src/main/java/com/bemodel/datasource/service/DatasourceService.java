package com.bemodel.datasource.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.mapper.DatasourceMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DatasourceService extends ServiceImpl<DatasourceMapper, Datasource> {

    private final Map<String, JdbcTemplate> jdbcCache = new ConcurrentHashMap<>();

    public Datasource getByCode(String dsCode) {
        return lambdaQuery().eq(Datasource::getDsCode, dsCode).one();
    }

    /** 按注册信息动态建立到产品库的 JDBC 连接（只读用途） */
    public JdbcTemplate jdbc(String dsCode) {
        return jdbcCache.computeIfAbsent(dsCode, code -> {
            Datasource ds = getByCode(code);
            if (ds == null) {
                throw new BizException("数据源不存在: " + code);
            }
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                    ds.getHost(), ds.getPort(), ds.getDbName()));
            dataSource.setUsername(ds.getUsername());
            dataSource.setPassword(ds.getPassword());
            return new JdbcTemplate(dataSource);
        });
    }

    public boolean testConnection(Datasource ds) {
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?connectTimeout=5000&socketTimeout=5000&useSSL=false&allowPublicKeyRetrieval=true",
                    ds.getHost(), ds.getPort(), ds.getDbName()));
            dataSource.setUsername(ds.getUsername());
            dataSource.setPassword(ds.getPassword());
            new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Datasource> listAll() {
        return lambdaQuery().orderByAsc(Datasource::getDsCode).list();
    }
}
