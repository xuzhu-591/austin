package com.java3y.austin.web.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.java3y.austin.common.constant.AustinConstant;
import com.java3y.austin.common.constant.CommonConstant;
import com.java3y.austin.common.enums.AuditStatus;
import com.java3y.austin.common.enums.MessageStatus;
import com.java3y.austin.common.enums.RespStatusEnum;
import com.java3y.austin.common.enums.TemplateType;
import com.java3y.austin.common.vo.BasicResultVO;
import com.java3y.austin.cron.xxl.entity.XxlJobInfo;
import com.java3y.austin.cron.xxl.service.CronTaskService;
import com.java3y.austin.cron.xxl.utils.XxlJobUtils;
import com.java3y.austin.support.dao.MessageTemplateDao;
import com.java3y.austin.support.domain.MessageTemplate;
import com.java3y.austin.web.service.MessageTemplateService;
import com.java3y.austin.web.vo.MessageTemplateParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import javax.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息模板管理 Service
 *
 * @author 3y
 * @date 2022/1/22
 */
@Service
public class MessageTemplateServiceImpl implements MessageTemplateService {


    @Autowired
    private MessageTemplateDao messageTemplateDao;

    @Autowired
    private CronTaskService cronTaskService;

    @Autowired
    private XxlJobUtils xxlJobUtils;

    @Override
    public Page<MessageTemplate> queryList(MessageTemplateParam param) {
        PageRequest pageRequest = PageRequest.of(param.getPage() - 1, param.getPerPage());
        String creator = StrUtil.isBlank(param.getCreator()) ? AustinConstant.DEFAULT_CREATOR : param.getCreator();
        return messageTemplateDao.findAll((Specification<MessageTemplate>) (root, query, cb) -> {
            List<Predicate> predicateList = new ArrayList<>();
            // 加搜索条件
            if (StrUtil.isNotBlank(param.getKeywords())) {
                predicateList.add(cb.like(root.get("name").as(String.class), "%" + param.getKeywords() + "%"));
            }
            predicateList.add(cb.equal(root.get("isDeleted").as(Integer.class), CommonConstant.FALSE));
            predicateList.add(cb.equal(root.get("creator").as(String.class), creator));
            Predicate[] p = new Predicate[predicateList.size()];
            // 查询
            query.where(cb.and(predicateList.toArray(p)));
            // 排序
            query.orderBy(cb.desc(root.get("updated")));
            return query.getRestriction();
        }, pageRequest);
    }

    @Override
    public Long count() {
        return messageTemplateDao.countByIsDeletedEquals(CommonConstant.FALSE);
    }

    @Override
    public MessageTemplate saveOrUpdate(MessageTemplate messageTemplate) {
        if (messageTemplate.getId() == null) {
            initStatus(messageTemplate);
        } else {
            resetStatus(messageTemplate);
        }

        messageTemplate.setUpdated(Math.toIntExact(DateUtil.currentSeconds()));
        return messageTemplateDao.save(messageTemplate);
    }


    @Override
    public void deleteByIds(List<Long> ids) {
        Iterable<MessageTemplate> messageTemplates = messageTemplateDao.findAllById(ids);
        messageTemplates.forEach(messageTemplate -> messageTemplate.setIsDeleted(CommonConstant.TRUE));
        for (MessageTemplate messageTemplate : messageTemplates) {
            if (messageTemplate.getCronTaskId() != null && messageTemplate.getCronTaskId() > 0) {
                cronTaskService.deleteCronTask(messageTemplate.getCronTaskId());
            }
        }
        messageTemplateDao.saveAll(messageTemplates);
    }

    @Override
    public MessageTemplate queryById(Long id) {
        return messageTemplateDao.findById(id).get();
    }

    @Override
    public void copy(Long id) {
        MessageTemplate messageTemplate = messageTemplateDao.findById(id).get();
        MessageTemplate clone = ObjectUtil.clone(messageTemplate).setId(null).setCronTaskId(null);
        messageTemplateDao.save(clone);
    }

    @Override
    public BasicResultVO startCronTask(Long id) {
        // 1.获取消息模板的信息
        MessageTemplate messageTemplate = messageTemplateDao.findById(id).get();

        // 2.动态创建或更新定时任务
        XxlJobInfo xxlJobInfo = xxlJobUtils.buildXxlJobInfo(messageTemplate);

        // 3.获取taskId(如果本身存在则复用原有任务，如果不存在则得到新建后任务ID)
        Integer taskId = messageTemplate.getCronTaskId();
        BasicResultVO basicResultVO = cronTaskService.saveCronTask(xxlJobInfo);
        if (taskId == null && RespStatusEnum.SUCCESS.getCode().equals(basicResultVO.getStatus()) && basicResultVO.getData() != null) {
            taskId = Integer.valueOf(String.valueOf(basicResultVO.getData()));
        }

        // 4. 启动定时任务
        if (taskId != null) {
            cronTaskService.startCronTask(taskId);
            MessageTemplate clone = ObjectUtil.clone(messageTemplate).setMsgStatus(MessageStatus.RUN.getCode()).setCronTaskId(taskId).setUpdated(Math.toIntExact(DateUtil.currentSeconds()));
            messageTemplateDao.save(clone);
            return BasicResultVO.success();
        }
        return BasicResultVO.fail();
    }

    @Override
    public BasicResultVO stopCronTask(Long id) {
        // 1.修改模板状态
        MessageTemplate messageTemplate = messageTemplateDao.findById(id).get();
        MessageTemplate clone = ObjectUtil.clone(messageTemplate).setMsgStatus(MessageStatus.STOP.getCode()).setUpdated(Math.toIntExact(DateUtil.currentSeconds()));
        messageTemplateDao.save(clone);

        // 2.暂停定时任务
        return cronTaskService.stopCronTask(clone.getCronTaskId());
    }


    /**
     * 初始化状态信息
     *
     * @param messageTemplate
     */
    private void initStatus(MessageTemplate messageTemplate) {
        messageTemplate.setFlowId(StrUtil.EMPTY)
                .setMsgStatus(MessageStatus.INIT.getCode()).setAuditStatus(AuditStatus.WAIT_AUDIT.getCode())
                .setCreator(StrUtil.isBlank(messageTemplate.getCreator()) ? AustinConstant.DEFAULT_CREATOR : messageTemplate.getCreator())
                .setUpdator(StrUtil.isBlank(messageTemplate.getUpdator()) ? AustinConstant.DEFAULT_UPDATOR : messageTemplate.getUpdator())
                .setTeam(StrUtil.isBlank(messageTemplate.getTeam()) ? AustinConstant.DEFAULT_TEAM : messageTemplate.getTeam())
                .setAuditor(StrUtil.isBlank(messageTemplate.getAuditor()) ? AustinConstant.DEFAULT_AUDITOR : messageTemplate.getAuditor())
                .setCreated(Math.toIntExact(DateUtil.currentSeconds()))
                .setIsDeleted(CommonConstant.FALSE);

    }

    /**
     * 1. 重置模板的状态
     * 2. 修改定时任务信息(如果存在)
     *
     * @param messageTemplate
     */
    private void resetStatus(MessageTemplate messageTemplate) {
        messageTemplate.setUpdator(messageTemplate.getUpdator())
                .setMsgStatus(MessageStatus.INIT.getCode()).setAuditStatus(AuditStatus.WAIT_AUDIT.getCode());

        if (messageTemplate.getCronTaskId() != null && TemplateType.CLOCKING.getCode().equals(messageTemplate.getTemplateType())) {
            XxlJobInfo xxlJobInfo = xxlJobUtils.buildXxlJobInfo(messageTemplate);
            cronTaskService.saveCronTask(xxlJobInfo);
            cronTaskService.stopCronTask(messageTemplate.getCronTaskId());
        }
    }


}
