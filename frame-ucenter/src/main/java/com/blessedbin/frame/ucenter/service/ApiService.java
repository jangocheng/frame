package com.blessedbin.frame.ucenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blessedbin.frame.common.Pagination;
import com.blessedbin.frame.common.exception.ParamCheckRuntimeException;
import com.blessedbin.frame.common.exception.ServiceRuntimeException;
import com.blessedbin.frame.ucenter.entity.SysPermission;
import com.blessedbin.frame.ucenter.entity.pojo.SysApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.blessedbin.frame.ucenter.entity.SysPermission.TYPE_API;

/**
 * Created by xubin on 2018/7/10.
 *
 * @author 37075
 * @date 2018/7/10
 * @time 9:31
 * @tool intellij idea
 */
@Service
@Log4j2
public class ApiService {

    @Autowired
    private ISysPermissionService permissionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DiscoveryClient discoveryClient;

    /**
     * 标记API扫描是否完成
     */
    private AtomicBoolean scanComplete = new AtomicBoolean(true);

    /**
     * 扫描API列表
     */
    @Transactional(rollbackFor = Exception.class)
    @Async
    public void scanApi() {

        synchronized (this){
            if(!scanComplete.get()){
                throw new ServiceRuntimeException("扫描未完成，请稍后重试");
            }
            scanComplete.set(false);
        }

        AtomicInteger addPoint = new AtomicInteger();
        AtomicInteger updatePoint = new AtomicInteger();


        log.info("开始扫描api列表");
        for(String s: discoveryClient.getServices()){
            List<ServiceInstance> serviceInstances = discoveryClient.getInstances(s);
            ServiceInstance instance = serviceInstances.get(0);
            log.debug("获取到服务：{}",s);

            // 判断服务是否可用
            URL url1;
            try {
                String sUrl = instance.getUri() + "/v2/api-docs";
                url1 = new URL(sUrl);
                url1.openStream();

                OpenAPI openAPI = new OpenAPIV3Parser().read(sUrl);
                log.debug("获取到API信息，开始处理.........");

                openAPI.getPaths().keySet().forEach(url -> {
                    PathItem path = openAPI.getPaths().get(url);

                    doMethod(addPoint, updatePoint, instance, url, path.getGet(), "GET");
                    doMethod(addPoint, updatePoint, instance, url, path.getPut(), "PUT");
                    doMethod(addPoint, updatePoint, instance, url, path.getPost(), "POST");
                    doMethod(addPoint, updatePoint, instance, url, path.getDelete(), "DELETE");
                    doMethod(addPoint, updatePoint, instance, url, path.getOptions(), "OPTIONS");
                    doMethod(addPoint, updatePoint, instance, url, path.getHead(), "HEAD");
                    doMethod(addPoint, updatePoint, instance, url, path.getPatch(), "PATCH");
                    doMethod(addPoint, updatePoint, instance, url, path.getTrace(), "TRACE");

                });

            } catch (Exception e1) {
                log.debug("未发现api-docs，跳过,message:{}",e1.getMessage());
            }
        }

        log.debug(">>> 新增权限点{}个，更新权限点{}个",addPoint.get(),updatePoint.get());
        scanComplete.set(true);
    }

    private void doMethod(AtomicInteger addPoint, AtomicInteger updatePoint, ServiceInstance instance, String url, Operation operation, String method) {
        if(operation == null) {
            return;
        }
        String code = TYPE_API + ":" + instance.getServiceId() + ":" + operation.getOperationId();

        SysPermission oldPermission = permissionService.selectByCode(code);

        try {
            // 构建新数据
            SysPermission permission = buildPermission(operation, code);
            SysApi api = buildApi(url, method, operation, instance.getServiceId(),permission.getPermissionId());
            permission.setAdditionInformation(objectMapper.writeValueAsString(api));

            if(oldPermission == null){
                // 新的点，插入
                permissionService.save(permission);

                log.debug("插入新的API：{}-{}",permission.getCode(),api);
                addPoint.addAndGet(1);
            } else {
                // 判断信息是否相同，不相同则更新，相同则跳过
                SysApi preAPi = objectMapper.readValue(oldPermission.getAdditionInformation(),SysApi.class);
                permission.setPermissionId(oldPermission.getPermissionId());

                if(!permission.equals(oldPermission) || !api.equals(preAPi)){
                    oldPermission.setName(operation.getSummary());
                    oldPermission.setPermissionId(oldPermission.getPermissionId());
                    oldPermission.setUpdateTime(LocalDateTime.now());
                    oldPermission.setCode(code);

                    oldPermission.setAdditionInformation(objectMapper.writeValueAsString(api));
                    permissionService.updateById(oldPermission);

                    log.debug("更新api {}：{}->{}",oldPermission.getCode(),preAPi,api);

                    updatePoint.addAndGet(1);
                }

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private SysApi buildApi(String url, String httpMethod, Operation operation,String serviceId, Integer permissionId) {
        SysApi api = new SysApi();
        api.setId(permissionId);
        api.setDescription(operation.getDescription());
        api.setName(operation.getSummary());
        api.setUrl(url);
        api.setMethod(httpMethod);
        api.setTags(StringUtils.collectionToDelimitedString(operation.getTags(),","));
        api.setServiceId(serviceId);
        api.setSort(1);
        return api;
    }

    private SysPermission buildPermission(Operation operation, String code) {
        SysPermission permission = new SysPermission();
        permission.setEnabled(true);
        permission.setSort(1);
        permission.setType(TYPE_API);
        permission.setName(operation.getSummary());
        permission.setCreateTime(LocalDateTime.now());
        permission.setUpdateTime(LocalDateTime.now());
        permission.setCode(code);
        return permission;
    }


    /**
     * TODO 根据标签和serviceID进行
     * @param pageNum
     * @param pageSize
     * @param tagList 标签列表
     * @param serviceIdList tag服务列表
     * @return
     */
    public Pagination<SysApi> getDataTables(Integer pageNum, Integer pageSize, List<String> tagList, List<String> serviceIdList) {
        Page<SysPermission> page = new Page<>(pageNum,pageSize);
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPermission::getType, TYPE_API);
        IPage<SysPermission> data = permissionService.page(page, wrapper);

        List<SysApi> collect = data.getRecords().stream().map(this::toSysApi).collect(Collectors.toList());
        return new Pagination<SysApi>(data.getCurrent(),data.getSize(),
                data.getTotal(),collect);
    }

    /**
     * @param uuid
     * @return
     */
    public List<SysApi> selectByUuid(String uuid) {
        List<SysPermission> permissions = permissionService.selectByUuidAndType(uuid, TYPE_API);
        return permissions.stream().map(this::toSysApi).collect(Collectors.toList());
    }

    public List<SysApi> selectByMenuId(Integer menuId) {
        return null;
    }

    public List<SysApi> selectAll() {
        List<SysPermission> permissions = permissionService.selectByType(TYPE_API);
        return permissions.stream().map(this::toSysApi).collect(Collectors.toList());
    }

    public SysApi selectByPk(Integer permissionId) {
        return null;
    }

    public SysApi getApi(Integer integer) {
        SysPermission permission = permissionService.selectByPkAndType(integer, TYPE_API);
        if(permission == null) {
            throw new ParamCheckRuntimeException();
        }
        return toSysApi(permission);
    }

    public List<SysApi> getApis(List<Integer> ids) {
        List<SysPermission> permissions = permissionService.selectByPksAndType(ids, TYPE_API);
        return permissions.stream().map(this::toSysApi).collect(Collectors.toList());
    }

    private SysApi toSysApi(SysPermission permission) {
        try {
            SysApi sysApi = objectMapper.readValue(permission.getAdditionInformation(), SysApi.class);
            sysApi.setId(permission.getPermissionId());
            return sysApi;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
