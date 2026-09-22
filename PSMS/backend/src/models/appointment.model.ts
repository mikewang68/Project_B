import mongoose from 'mongoose';

/**
 * 公路预约与叫号。
 *
 * `documents` 内嵌：资质附件条目少且随预约整体读写。
 */
export interface AppointmentDocument {
  type?: string;
  url?: string;
  verified?: boolean;
}

export interface AppointmentDoc {
  _id: string;
  appointmentId: string;
  vehiclePlate: string;
  vehicleType: string;
  driverName: string;
  driverPhone: string;
  driverIdCard: string;
  company: string;
  cargoType: string;
  estimatedWeight: number;
  plannedArriveTime: Date;
  actualArriveTime?: Date | null;
  checkInTime?: Date | null;
  calledAt?: Date | null;
  enterTime?: Date | null;
  exitTime?: Date | null;
  queueNumber?: number | null;
  status: string;
  gateNo?: string | null;
  parkingBay?: string | null;
  route?: string | null;
  documents?: AppointmentDocument[];
  remarks?: string | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const appointmentSchema = new mongoose.Schema<AppointmentDoc>(
  {
    _id: { type: String, required: true },
    appointmentId: { type: String, required: true, unique: true },
    vehiclePlate: { type: String, required: true },
    vehicleType: { type: String, required: true },
    driverName: { type: String, required: true },
    driverPhone: { type: String, required: true },
    driverIdCard: { type: String, required: true },
    company: { type: String, default: '' },
    cargoType: { type: String, required: true },
    estimatedWeight: { type: Number, default: 0 },
    plannedArriveTime: { type: Date, required: true },
    actualArriveTime: { type: Date, default: null },
    checkInTime: { type: Date, default: null },
    calledAt: { type: Date, default: null },
    enterTime: { type: Date, default: null },
    exitTime: { type: Date, default: null },
    queueNumber: { type: Number, default: null },
    status: {
      type: String,
      required: true,
      enum: [
        'PENDING',
        'APPROVED',
        'CHECKED_IN',
        'QUEUED',
        'CALLED',
        'ON_SITE',
        'COMPLETED',
        'CANCELLED',
        'NO_SHOW',
      ],
      default: 'PENDING',
    },
    gateNo: { type: String, default: null },
    parkingBay: { type: String, default: null },
    route: { type: String, default: null },
    documents: {
      type: [
        {
          type: { type: String },
          url: { type: String },
          verified: { type: Boolean, default: false },
        },
      ],
      default: [],
    },
    remarks: { type: String, default: null },
  },
  { timestamps: true, collection: 'appointments', versionKey: false },
);

appointmentSchema.index({ plannedArriveTime: 1 });
appointmentSchema.index({ status: 1, queueNumber: 1 });
appointmentSchema.index({ vehiclePlate: 1 });

export const Appointment = mongoose.model<AppointmentDoc>('Appointment', appointmentSchema);
