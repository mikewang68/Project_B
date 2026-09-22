import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as appointmentService from '../services/appointment.service.js';
import { pathParam, q, qList, qNum } from '../lib/http.js';

export const appointmentsRouter = Router();

// API-010 预约列表
appointmentsRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const result = await appointmentService.listAppointments({
      vehiclePlate: q(req, 'vehiclePlate'),
      statuses: qList(req, 'statuses'),
      plannedDate: q(req, 'plannedDate'),
      page: qNum(req, 'page'),
      pageSize: qNum(req, 'pageSize'),
    });
    res.json({
      ok: true,
      data: {
        apiId: 'API-010',
        operationId: 'GET_mock_appointments',
        items: result.items,
        total: result.total,
        page: result.page,
        pageSize: result.pageSize,
      },
    });
  } catch (err) {
    next(err);
  }
});

// 预约详情
appointmentsRouter.get('/:id', optionalAuth, async (req, res, next) => {
  try {
    const apt = await appointmentService.getAppointmentById(pathParam(req));
    res.json({ ok: true, data: { apiId: 'API-010', items: [apt] } });
  } catch (err) {
    next(err);
  }
});

// API-011 预约状态流转：approve / checkin / call / enter / complete
appointmentsRouter.post('/:id', authenticate, auditOperation('appointmentAction'), async (req, res, next) => {
  try {
    const { action, ...data } = req.body as Record<string, unknown> & { action?: string };
    const actorId = req.user!.actorId;
    let result: unknown;

    switch (action) {
      case 'approve':
        result = await appointmentService.approveAppointment(pathParam(req), actorId);
        break;
      case 'checkin':
        result = await appointmentService.checkInAppointment(pathParam(req), actorId);
        break;
      case 'call':
        result = await appointmentService.callAppointment(pathParam(req), data.gateNo as string | undefined, actorId);
        break;
      case 'enter':
        result = await appointmentService.enterAppointment(pathParam(req), actorId);
        break;
      case 'complete':
        result = await appointmentService.completeAppointment(pathParam(req), actorId);
        break;
      default:
        res.status(400).json({
          ok: false,
          errorCode: 'DEMO-SCENARIO-001',
          message: `未知的预约动作：${String(action)}`,
        });
        return;
    }

    res.json({ ok: true, data: { apiId: 'API-011', items: [result] } });
  } catch (err) {
    next(err);
  }
});
