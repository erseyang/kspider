package top.kangert.kspider.service.impl;

import top.kangert.kspider.config.SpiderConfig;
import top.kangert.kspider.dao.SpiderTaskRepository;
import top.kangert.kspider.domain.SpiderTask;
import top.kangert.kspider.enums.TaskStateEnum;
import top.kangert.kspider.exception.BaseException;
import top.kangert.kspider.exception.ExceptionCodes;
import top.kangert.kspider.job.SpiderJobManager;
import top.kangert.kspider.service.BaseService;
import top.kangert.kspider.service.SpiderTaskService;
import top.kangert.kspider.util.PageInfo;

import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

@Service
@Slf4j
public class SpiderTaskServiceImpl extends BaseService implements SpiderTaskService {

    @Resource
    private SpiderTaskRepository spiderTaskRepository;

    @Resource
    private SpiderJobManager spiderJobManager;

    @Autowired
    @SuppressWarnings("all")
    private PlatformTransactionManager txManager;

    @Autowired
    private SpiderConfig spiderConfig;

    /**
     * 项目启动后自动添加需要执行的定时任务
     */
    @PostConstruct
    public void initializeJobs() {
        TransactionTemplate tmpl = new TransactionTemplate(txManager);
        tmpl.execute(new TransactionCallbackWithoutResult() {
            @Override
            // 保证 doInTransactionWithoutResult 方法里的代码在事务中
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                // 清空所有流程的下次执行时间
                clearNextExecuteTime();
                // 获取所有启用定时任务的流程
                List<SpiderTask> tasks = findByJobEnabled(Boolean.TRUE);
                if (tasks != null && !tasks.isEmpty()) {
                    for (SpiderTask task : tasks) {
                        if (StrUtil.isNotBlank(task.getCron())) {
                            Date nextTime = spiderJobManager.addJob(task);
                            log.info("初始化定时任务：{}，下次执行时间：{}", task.getTaskName(),
                                    DateUtil.format(nextTime, "yyyy-MM-dd HH:mm:ss"));
                            if (nextTime != null) {
                                task.setNextExecuteTime(nextTime);
                                updateNextExecuteTime(task);
                            }
                        }
                    }
                }
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpiderTask add(Map<String, Object> params) {
        checkParams(params, new String[] { "flowId", "matedata", "taskName" });
        Map<String,Object> matedata = JSONUtil.toBean((String)params.get("matedata"),
                new TypeReference<Map<String, Object>>() {}, false);
        // 判断平台类型
        if (!matedata.containsKey("classNo")) {
            // 校验线上元数据
            checkParams(matedata, new String[] { "username", "enterpriseName", "password", "className" });
        } else {
            // 校验线下元数据
            checkParams(matedata, new String[] { "classId", "classNo"});
        }
        SpiderTask spiderTask = transformEntity(params, SpiderTask.class);
        if (spiderTask.getFlowId() != null) {
            // 如果任务正在执行，则设置下次执行时间
            if (spiderTask.getJobEnabled() && StrUtil.isNotBlank(spiderTask.getCron())) {
                CronTrigger trigger = TriggerBuilder.newTrigger()
                        .withSchedule(CronScheduleBuilder.cronSchedule(spiderTask.getCron()))
                        .build();
                spiderTask.setNextExecuteTime(trigger.getFireTimeAfter(null));

                // 重新发布任务
                if (spiderJobManager.removeJob(spiderTask.getFlowId())) {
                    spiderJobManager.addJob(spiderTask);
                }
            }
        }

        return spiderTaskRepository.save(spiderTask);
    }

    @Override
    public SpiderTask queryItem(Long taskId) {
        Optional<SpiderTask> optional = spiderTaskRepository.findById(taskId);
        return optional.get();
    }

    @Override
    public PageInfo<SpiderTask> queryItems(Map<String, Object> params) {
        Pageable pageable = processPage(params);

        Page<SpiderTask> spiderTaskList = spiderTaskRepository.findAll(multipleConditionsBuilder(params), pageable);

        return new PageInfo<SpiderTask>(spiderTaskList);
    }

    @Override
    public Integer getRunningCountByFlowId(Map<String, Object> params) {
        checkParams(params, new String[] { "flowId" });

        Long flowId = (Long) params.get("flowId");

        return spiderTaskRepository.countByFlowIdAndupdateTimeIsNull(flowId);
    }

    @Override
    public Long getMaxTaskIdByFlowId(Long flowId) {
        return spiderTaskRepository.findTaskIdByFlowIdOrderByupdateTimeDesc(flowId).stream().findFirst().orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Map<String, Object> params) {
        checkParams(params, new String[] { "taskId" });

        Long taskId = (Long) params.get("taskId");

        // 删除定时器
        if (spiderJobManager.removeJob(taskId)) {
            // 删除SpiderFlow
            spiderTaskRepository.deleteById(taskId);
        }
    }

    @Override
    public void edit(Map<String, Object> params) {
        checkParams(params, new String[] { "taskId" });

        // 获取任务
        Long taskId = Convert.toLong(params.get("taskId"));

        if (params.containsKey("matedata")) {
            // 校验元数据
            Map<String,Object> matedata = JSONUtil.toBean((String)params.get("matedata"),
                    new TypeReference<Map<String, Object>>() {}, false);
            // 判断平台类型
            if (!matedata.containsKey("classNo")) {
                // 校验线上元数据
                checkParams(matedata, new String[] { "username", "enterpriseName", "password", "className" });
            } else {
                // 校验线下元数据
                checkParams(matedata, new String[] { "classId", "classNo"});
            }
        }

        // 实体查询
        SpiderTask spiderTask = queryItem(taskId);

        if (spiderTask == null) {
            throw new BaseException(ExceptionCodes.DB_DATA_WRONG);
        }

        // 对象值拷贝
        copyProperties(params, spiderTask);

        // 如果任务正在执行，则设置下次执行时间
        if (StrUtil.isNotBlank(spiderTask.getCron()) && spiderTask.getJobEnabled()) {
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withSchedule(CronScheduleBuilder.cronSchedule(spiderTask.getCron()))
                    .build();
            spiderTask.setNextExecuteTime(trigger.getFireTimeAfter(null));
            if (spiderJobManager.removeJob(spiderTask.getFlowId())) {
                spiderJobManager.addJob(spiderTask);
            }
        }

        spiderTaskRepository.save(spiderTask);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeCountIncrement(Long id, Date lastExecuteTime, Date nextExecuteTime) {
        if (nextExecuteTime == null) {
            spiderTaskRepository.executeCountIncrement(lastExecuteTime, id);
        } else {
            spiderTaskRepository.executeCountIncrementAndExecuteNextTime(lastExecuteTime, nextExecuteTime, id);
        }
    }

    @Override
    public List<SpiderTask> findByJobEnabled(Boolean jobEnabled) {
        return spiderTaskRepository.findByJobEnabled(jobEnabled);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateNextExecuteTime(SpiderTask task) {
        spiderTaskRepository.updateNextExecuteTime(task.getNextExecuteTime(), task.getFlowId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCronAndNextExecuteTime(Long taskId, String cron) {
        // 创建触发器
        CronTrigger trigger = TriggerBuilder.newTrigger().withSchedule(CronScheduleBuilder.cronSchedule(cron)).build();
        // 删除定时任务
        if (spiderJobManager.removeJob(taskId)) {
            // 计算下次执行时间后，更新流程
            spiderTaskRepository.updateCronAndNextExecuteTime(taskId, cron, trigger.getFireTimeAfter(null));
            SpiderTask task = queryItem(taskId);
            // 定时任务已开启
            if (task.getJobEnabled()) {
                // 添加任务
                spiderJobManager.addJob(task);
            } else {
                spiderTaskRepository.updateCronAndNextExecuteTime(taskId, cron, null);
            }
        } else {
            spiderTaskRepository.updateCronAndNextExecuteTime(taskId, cron, null);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearNextExecuteTime() {
        spiderTaskRepository.clearNextExecuteTime();
    }

    @Override
    public void run(Map<String, Object> params) {
        checkParams(params, new String[] { "taskId" });
        // 获取任务
        Long taskId = Convert.toLong(params.get("taskId"));
        SpiderTask spiderTask = spiderTaskRepository.findById(taskId).get();
        // 获取任务状态
        if (spiderTask.getRunState() == TaskStateEnum.TASK_RUNNING.getTypeCode()) {
            throw new BaseException(ExceptionCodes.CURRENT_TASK_RUN);
        }
        if (spiderTask.getJobEnabled()) {
            scheduleStart(taskId);
        }

        spiderJobManager.run(taskId);
    }

    @Override
    @Transactional
    public void stop(Map<String, Object> params) {
        checkParams(params, new String[] { "taskId" });

        // 获取任务
        Long taskId = Convert.toLong(params.get("taskId"));
        spiderTaskRepository.updateJobEnabled(taskId, Boolean.FALSE);
        spiderTaskRepository.updateNextExecuteTime(null, taskId);
        spiderJobManager.removeJob(taskId);
    }

    private void scheduleStart(Long taskId) {
        // 先尝试删除任务
        if (spiderJobManager.removeJob(taskId)) {
            // 设置定时任务状态为开启
            spiderTaskRepository.updateJobEnabled(taskId, Boolean.TRUE);
            SpiderTask spiderTask = queryItem(taskId);
            if (spiderTask != null) {
                // 添加任务
                Date nextExecuteTime = spiderJobManager.addJob(spiderTask);
                if (nextExecuteTime != null) {
                    // 更新下次执行时间
                    spiderTask.setNextExecuteTime(nextExecuteTime);
                    spiderTaskRepository.updateNextExecuteTime(spiderTask.getNextExecuteTime(), spiderTask.getFlowId());
                }
            }
        }
    }

    @Override
    public void download(Map<String, Object> params, HttpServletResponse response) {
        checkParams(params, new String[] { "taskId" });
        // 获取任务
        Long taskId = Convert.toLong(params.get("taskId"));
        SpiderTask spiderTask = queryItem(taskId);
        String matedata = spiderTask.getMatedata();
        Map<String, Object> variables = JSONUtil.toBean(matedata, new TypeReference<Map<String, Object>>() {}, false);
        String fileName = null;
        String filePath = null;
        BufferedInputStream inputStream = null;
        BufferedOutputStream outputStream = null;

        // 判断平台类型
        if (variables.containsKey("classNo")) {
            fileName = variables.get("classNo") + ".json";
            // json文件路径
            String jsonPath = spiderConfig.getWorkspace() + File.separator + "fileData" + File.separator + "json" + File.separator + fileName;
            if (!FileUtil.exist(jsonPath)) {
                throw new BaseException(ExceptionCodes.FILE_NOT_EXIST);
            }
            inputStream = FileUtil.getInputStream(jsonPath);
        } else {
            // zip压缩路径
            String enterpriseName = Convert.toStr(variables.get("enterpriseName"));
            String className = Convert.toStr(variables.get("className"));
            long currentTime = System.currentTimeMillis();
            // 上级目录
            String upCatalog = taskId + "_" + enterpriseName;
            String srcPath = spiderConfig.getWorkspace() + File.separator + "fileData" + File.separator + upCatalog;
            filePath = spiderConfig.getWorkspace() + File.separator + "fileData" + File.separator + "zip"
                    + File.separator + enterpriseName + currentTime + ".zip";
            if (!FileUtil.exist(srcPath)) {
                throw new BaseException(ExceptionCodes.FILE_NOT_EXIST);
            }
            // 线上文件压缩
            ZipUtil.zip(srcPath, filePath);
            fileName = taskId + "-" + enterpriseName + "-" + className + ".zip";
            inputStream = FileUtil.getInputStream(filePath);
        }

        try {
            // 重置响应头信息
            response.reset();
            response.addHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(fileName, "UTF-8"));
            response.setContentType("application/octet-stream;charset=UTF-8");
            outputStream = new BufferedOutputStream(response.getOutputStream());
            byte[] readBytes = new byte[inputStream.available()];
            inputStream.read(readBytes);
            response.setHeader("Content-Length", "" + readBytes.length);
            outputStream.write(readBytes);
            outputStream.flush();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new BaseException(ExceptionCodes.FILE_EXPORT_FAILED);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
             // 删除线上文件压缩包
             if (fileName.lastIndexOf("zip") > 0) {
                FileUtil.del(filePath);
            }
        }
    }
}
