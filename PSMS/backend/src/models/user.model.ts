import mongoose from 'mongoose';

/**
 * 用户账号。
 *
 * 说明：`_id` 使用字符串形式的 actorId（如 ACTOR-ADMIN），
 * 与 JWT payload 中的 actorId 保持一致，便于审计日志直接引用。
 */
export interface UserDoc {
  _id: string;
  actorId: string;
  username: string;
  passwordHash: string;
  displayName: string;
  roleCode: string;
  dataScope: string[];
  online: boolean;
  lastLoginAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const userSchema = new mongoose.Schema<UserDoc>(
  {
    _id: { type: String, required: true },
    actorId: { type: String, required: true, unique: true, index: true },
    username: { type: String, required: true, unique: true },
    passwordHash: { type: String, required: true },
    displayName: { type: String, required: true },
    // 注：后端角色集与前端契约的 13 个 RoleCode（DISPATCHER 等）尚未对齐，
    // 详见 docs/PSMS-数据库设计文档.md 的「契约差异」说明
    roleCode: {
      type: String,
      required: true,
      enum: ['super_admin', 'admin', 'scheduler', 'dispatcher', 'operator', 'viewer'],
    },
    dataScope: { type: [String], default: [] },
    online: { type: Boolean, default: false },
    lastLoginAt: { type: Date, default: null },
  },
  { timestamps: true, collection: 'users', versionKey: false },
);

userSchema.index({ roleCode: 1 });
userSchema.index({ dataScope: 1 });

export const User = mongoose.model<UserDoc>('User', userSchema);
