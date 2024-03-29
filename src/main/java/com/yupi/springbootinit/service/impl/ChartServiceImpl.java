package com.yupi.springbootinit.service.impl;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.Retryer;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.constant.GenTaskStatus;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.manager.RedisLimiterManager;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.BiResponse;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.ExcelUtils;
import com.yupi.springbootinit.utils.SqlUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
* @author 1052295067
* @description 针对表【chart(图表信息表)】的数据库操作Service实现
* @createDate 2024-02-26 19:19:05
*/
@Service
public class ChartServiceImpl extends ServiceImpl<ChartMapper, Chart>
    implements ChartService{

    @Resource
    private UserService userService;
    @Resource
    private AiManager aiManager;
    @Resource
    private ChartMapper chartMapper;
    @Resource
    private RedisLimiterManager redisLimiterManager;
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;
    @Resource
    private Retryer retryer;

    /**
     * 智能分析（同步）
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @Transactional
    @Override
    public BaseResponse<BiResponse> genChartByAi(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {

        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        // 校验
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        // 校验文件
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        // 校验文件大小
        final long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过 1MB");
        // 校验文件后缀
        String suffix = FileUtil.getSuffix(originalFilename);
        final List<String> validFileSuffixList = Arrays.asList("xlsx", "xls");
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 限流判断，每个用户一个限流器
        redisLimiterManager.doRateLimit("genChartByAi_" + String.valueOf(loginUser.getId()));

        // 无需写prompt，直接使用设置了职责的模型
//        final String prompt = "你是一个数据分析师和前端开发专家，接下来我会按照以下固定格式给你提供内容：\n" +
//                "分析需求：\n" +
//                "{数据分析的需求或者目标}\n" +
//                "原始数据：\n" +
//                "{csv格式的原始数据，用,作为分隔符}\n" +
//                "请根据这两部分内容，按照以下指定格式生成内容（此外不要输出任何多余的开头、结尾、注释）\n" +
//                "【【【【【\n" +
//                "{前端 Echarts V5 的 option 配置对象的json格式代码，合理地将数据进行可视化，不要生成任何多余的内容，比如注释}\n" +
//                "【【【【【\n" +
//                "{明确的数据分析结论、越详细越好，不要生成多余的注释}";

        final long biModelId = 1762759704788291586L;

        // 构造用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：").append("\n");
        String userGoal = goal;
        if(StringUtils.isNotBlank(chartType)){
            userGoal += "，请使用" + chartType;
        }
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：").append("\n");
        // 压缩后的数据
        List<Map<Integer, String>> excelList = ExcelUtils.readExcel(multipartFile);
        String csvData = ExcelUtils.excel2csv(excelList);
        userInput.append(csvData).append("\n");

        String answer = aiManager.doChat(biModelId, String.valueOf(userInput));
        String[] splits = answer.split("【【【【【");
        if(splits.length < 3){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成错误");
        }
        String genChart = splits[1].trim();
        String genResult = splits[2].trim();
        // 插入到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartType(chartType);
        chart.setGenChart(genChart);
        chart.setGenResult(genResult);
        chart.setUserId(loginUser.getId());
        boolean saveResult = save(chart);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "图表保存失败");

        // 为每次分析创建一个数据存储表
        Long chartId = chart.getId();
        String chartDataTableName = "chart_"+chartId;
        List<String> fieldNameList = new ArrayList<>(excelList.remove(0).values());
        chartMapper.createTable(chartDataTableName, fieldNameList);
        List<Collection<String>> dataList = excelList.stream()
                .map(Map::values)
                .collect(Collectors.toList());
        chartMapper.insertValues(chartDataTableName, dataList);

        // 对生成的结果进行封装
        BiResponse biResponse = new BiResponse();
        biResponse.setGenChart(genChart);
        biResponse.setGenResult(genResult);
        biResponse.setChartId(chart.getId());

        return ResultUtils.success(biResponse);
    }

    /**
     * 智能分析（异步）
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @Transactional
    @Override
    public BaseResponse<BiResponse> genChartByAiAsync(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {

        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        // 校验
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        // 校验文件
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        // 校验文件大小
        final long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过 1MB");
        // 校验文件后缀
        String suffix = FileUtil.getSuffix(originalFilename);
        final List<String> validFileSuffixList = Arrays.asList("xlsx", "xls");
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 限流判断，每个用户一个限流器
        redisLimiterManager.doRateLimit("genChartByAi_" + loginUser.getId());

        // 无需写prompt，直接使用设置了职责的模型
//        final String prompt = "你是一个数据分析师和前端开发专家，接下来我会按照以下固定格式给你提供内容：\n" +
//                "分析需求：\n" +
//                "{数据分析的需求或者目标}\n" +
//                "原始数据：\n" +
//                "{csv格式的原始数据，用,作为分隔符}\n" +
//                "请根据这两部分内容，按照以下指定格式生成内容（此外不要输出任何多余的开头、结尾、注释）\n" +
//                "【【【【【\n" +
//                "{前端 Echarts V5 的 option 配置对象的json格式代码，合理地将数据进行可视化，不要生成任何多余的内容，比如注释}\n" +
//                "【【【【【\n" +
//                "{明确的数据分析结论、越详细越好，不要生成多余的注释}";

        final long biModelId = 1762759704788291586L;

        // 构造用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：").append("\n");
        String userGoal = goal;
        if(StringUtils.isNotBlank(chartType)){
            userGoal += "，请使用" + chartType;
        }
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：").append("\n");
        // 压缩后的数据
        List<Map<Integer, String>> excelList = ExcelUtils.readExcel(multipartFile);
        String csvData = ExcelUtils.excel2csv(excelList);
        userInput.append(csvData).append("\n");


        // 插入到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartType(chartType);
        chart.setStatus(GenTaskStatus.WAIT);
        chart.setUserId(loginUser.getId());
        boolean saveResult = save(chart);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "图表保存失败");

        // 为每次分析创建一个数据存储表
        Long chartId = chart.getId();
        String chartDataTableName = "chart_"+chartId;
        List<String> fieldNameList = new ArrayList<>(excelList.remove(0).values());
        chartMapper.createTable(chartDataTableName, fieldNameList);
        List<Collection<String>> dataList = excelList.stream()
                .map(Map::values)
                .collect(Collectors.toList());
        boolean insertResult = chartMapper.insertValues(chartDataTableName, dataList);
        ThrowUtils.throwIf(!insertResult, ErrorCode.SYSTEM_ERROR, "图表数据保存失败");

        // 执行 AI 生成任务
        executeTaskWithRetry(biModelId, chartId, userInput);

        // 对生成的结果进行封装
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(chart.getId());

        return ResultUtils.success(biResponse);
    }

    /**
     * 执行 AI 生成任务
     * @param biModelId
     * @param chartId
     * @param userInput
     */
    private void executeTaskWithRetry(Long biModelId, Long chartId, StringBuilder userInput){
        CompletableFuture.runAsync(() -> {
            // 当执行任务出错时进行重试
            try {
                retryer.call(() -> {
                    //先修改图表任务状态为“执行中”，等执行成功后修改位“已完成”并保存执行结果，执行失败后，状态修改为“失败”，记录任务失败信息
                    Chart updateChart = new Chart();
                    updateChart.setId(chartId);
                    updateChart.setStatus(GenTaskStatus.RUNNING);
                    boolean b = updateById(updateChart);
                    if(!b){
                        handlerChartUpdateError(chartId, "更新图表执行中状态失败");
                        return false;
                    }

                    //调用AI
                    String answer = aiManager.doChat(biModelId, String.valueOf(userInput));
                    String[] splits = answer.split("【【【【【");
                    if(splits.length < 3){
                        handlerChartUpdateError(chartId, "AI 生成错误");
                        return false;
                    }
                    String genChart = splits[1].trim();
                    String genResult = splits[2].trim();
                    Chart updateChart2 = new Chart();
                    updateChart2.setId(chartId);
                    updateChart2.setGenChart(genChart);
                    updateChart2.setGenResult(genResult);
                    updateChart2.setStatus(GenTaskStatus.SUCCEED);
                    boolean b2 = updateById(updateChart2);
                    if(!b2){
                        handlerChartUpdateError(chartId, "更新图表成功状态失败");
                        return false;
                    }
                    return true;
                });
            } catch (ExecutionException | RetryException e) {
                throw new RuntimeException(e);
            }
        }, threadPoolExecutor)
        // 捕获线程池满时抛出的 RejectedExecutionException
        .whenComplete((result, throwable) -> {
            if (throwable instanceof CompletionException) {
                // 线程池满时进行重试
                if(throwable.getCause() instanceof RejectedExecutionException) {
                    log.error("线程池满了，当前任务被拒绝: " + chartId);
                    // 执行重试
                    executeTaskWithRetry(biModelId, chartId, userInput);
                }
            }
        });
    }

    private void handlerChartUpdateError(Long chartId, String execMessage){
        Chart updateChartResult = new Chart();
        updateChartResult.setId(chartId);
        updateChartResult.setStatus(GenTaskStatus.FAILED);
        updateChartResult.setExecMessage(execMessage);
        boolean updateResult = updateById(updateChartResult);
        if(!updateResult){
            log.error("更新图表失败状态失败" + chartId + ", " + execMessage);
        }
    }

    /**
     * 获取我的图表
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @Override
    public BaseResponse<Page<Chart>> listMyChartByPage(ChartQueryRequest chartQueryRequest, HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chartQueryRequest.setUserId(loginUser.getId());
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));

        return ResultUtils.success(chartPage);
    }

    /**
     * 获取查询包装类
     *
     * @param chartQueryRequest
     * @return
     */
    private QueryWrapper<Chart> getQueryWrapper(ChartQueryRequest chartQueryRequest) {
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        if (chartQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chartQueryRequest.getId();
        String name = chartQueryRequest.getName();
        String goal = chartQueryRequest.getGoal();
        String chartType = chartQueryRequest.getChartType();
        Long userId = chartQueryRequest.getUserId();
        String sortField = chartQueryRequest.getSortField();
        String sortOrder = chartQueryRequest.getSortOrder();

        queryWrapper.eq(id != null && id > 0, "id", id);
        queryWrapper.like(StringUtils.isNotBlank(name), "name", name);
        queryWrapper.eq(StringUtils.isNotBlank(goal), "goal", goal);
        queryWrapper.eq(StringUtils.isNotBlank(chartType), "chartType", chartType);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }
}




