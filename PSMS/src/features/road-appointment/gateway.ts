import { z } from 'zod';

import {
  api011RequestSchema,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do008Schema,
  type ApiErrorEnvelope,
  type Appointment,
} from '../../contracts';
import { createAppFetch } from '../../runtime/appBasePath';

const appointmentListDataSchema = z.object({
  apiId: z.literal('API-010'),
  operationId: z.literal('GET_mock_appointments'),
  now: z.string(),
  scenarioId: z.string(),
  items: z.array(do008Schema),
}).strict();

const appointmentTransitionDataSchema = z.object({
  apiId: z.literal('API-011'),
  operationId: z.literal('POST_mock_appointments_id_transition'),
  now: z.string(),
  scenarioId: z.string(),
  items: z.array(do008Schema).length(1),
}).strict();

export type AppointmentListResult =
  | Readonly<{ ok: true; items: readonly Appointment[]; scenarioId: string; now: string }>
  | ApiErrorEnvelope;

export type AppointmentTransitionResult =
  | Readonly<{ ok: true; appointment: Appointment; scenarioId: string; now: string }>
  | ApiErrorEnvelope;

export type RoadAppointmentGateway = Readonly<{
  listAppointments: (role?: string) => Promise<AppointmentListResult>;
  transitionAppointment: (
    appointmentId: string,
    input: z.infer<typeof api011RequestSchema>,
  ) => Promise<AppointmentTransitionResult>;
}>;

async function parseResponse(response: Response) {
  const body: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(body);
  if (success.success) return success.data;
  return apiErrorEnvelopeSchema.parse(body);
}

export function createRoadAppointmentGateway(
  fetcher: typeof fetch = fetch,
): RoadAppointmentGateway {
  const appFetcher = createAppFetch(fetcher);
  return {
    async listAppointments(role) {
      const query = role ? `?role=${encodeURIComponent(role)}` : '';
      const response = await parseResponse(await appFetcher(`/mock/appointments${query}`));
      if (!response.ok) return response;
      const data = appointmentListDataSchema.parse(response.data);
      return { ok: true, items: data.items, scenarioId: data.scenarioId, now: data.now };
    },

    async transitionAppointment(appointmentId, input) {
      const body = api011RequestSchema.parse(structuredClone(input));
      const response = await parseResponse(await appFetcher(
        `/mock/appointments/${encodeURIComponent(appointmentId)}/transition`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(body),
        },
      ));
      if (!response.ok) return response;
      const data = appointmentTransitionDataSchema.parse(response.data);
      if (data.items[0]?.id !== appointmentId) {
        throw new Error('预约状态回执与当前车辆不一致。');
      }
      return {
        ok: true,
        appointment: data.items[0],
        scenarioId: data.scenarioId,
        now: data.now,
      };
    },
  };
}
