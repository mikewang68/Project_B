import type { Api022SuccessEnvelope, ApiErrorEnvelope, ConfigVersion } from '../../contracts';
import { createSystemSettingsDraft, updateSystemSettingsDraft } from './systemSettingsDraft';
import type {
  EditableConfigKey,
  EditableConfigValues,
  SystemSettingsCommandFeedback,
  SystemSettingsDraft,
  SystemSettingsGroupId,
} from './systemSettingsTypes';

export type SystemSettingsReadState = 'idle' | 'loading' | 'success' | 'error';

export type SystemSettingsValidationErrors = Readonly<{
  fieldErrors: Readonly<Partial<Record<EditableConfigKey, string>>>;
  formErrors: readonly string[];
}>;

export type SystemSettingsWorkflowState = Readonly<{
  selectedGroup: SystemSettingsGroupId;
  draft?: SystemSettingsDraft;
  dirtyFields: readonly EditableConfigKey[];
  validationErrors: SystemSettingsValidationErrors;
  pending: boolean;
  readState: SystemSettingsReadState;
  readObservation?: Api022SuccessEnvelope | ApiErrorEnvelope;
  readError?: string;
  lastFeedback?: SystemSettingsCommandFeedback;
}>;

export type SystemSettingsWorkflowStore = Readonly<{
  getState(): SystemSettingsWorkflowState;
  subscribe(listener: () => void): () => void;
  selectGroup(selectedGroup: SystemSettingsGroupId): void;
  setReadPending(): void;
  recordReadSuccess(observation: Api022SuccessEnvelope): void;
  recordReadFailure(message: string, observation?: ApiErrorEnvelope): void;
  beginDraft(config: ConfigVersion): void;
  updateDraftField<K extends EditableConfigKey>(key: K, value: EditableConfigValues[K]): void;
  setDraftReason(reason: string): void;
  setValidationErrors(errors: SystemSettingsValidationErrors): void;
  setPending(pending: boolean): void;
  recordFeedback(feedback: SystemSettingsCommandFeedback): void;
  discardDraft(): void;
  reset(): void;
}>;

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function initialState(): SystemSettingsWorkflowState {
  return deepFreeze({
    selectedGroup: 'overview',
    dirtyFields: [],
    validationErrors: { fieldErrors: {}, formErrors: [] },
    pending: false,
    readState: 'idle',
  });
}

function sameValue(left: unknown, right: unknown): boolean {
  return Object.is(left, right);
}

export function createSystemSettingsWorkflowStore(): SystemSettingsWorkflowStore {
  let state = initialState();
  let draftOrigin: EditableConfigValues | undefined;
  const listeners = new Set<() => void>();

  const replace = (candidate: SystemSettingsWorkflowState): void => {
    state = deepFreeze(candidate);
    for (const listener of listeners) listener();
  };

  const clearDraftState = (): Pick<
    SystemSettingsWorkflowState,
    'dirtyFields' | 'validationErrors'
  > => ({
    dirtyFields: [],
    validationErrors: { fieldErrors: {}, formErrors: [] },
  });

  return Object.freeze({
    getState: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectGroup: (selectedGroup) => replace({ ...state, selectedGroup }),
    setReadPending: () => replace({
      ...state,
      readState: 'loading',
      readObservation: undefined,
      readError: undefined,
    }),
    recordReadSuccess: (readObservation) => replace({
      ...state,
      readState: 'success',
      readObservation,
      readError: undefined,
    }),
    recordReadFailure: (readError, readObservation) => replace({
      ...state,
      readState: 'error',
      ...(readObservation ? { readObservation } : { readObservation: undefined }),
      readError,
    }),
    beginDraft: (config) => {
      const draft = createSystemSettingsDraft(config);
      draftOrigin = structuredClone(draft.values);
      replace({ ...state, draft, ...clearDraftState(), lastFeedback: undefined });
    },
    updateDraftField: (key, value) => {
      if (!state.draft) throw new Error('System settings draft is not active.');
      const draft = updateSystemSettingsDraft(state.draft, key, value);
      const dirtyFields = draftOrigin
        ? (Object.keys(draftOrigin) as EditableConfigKey[])
          .filter((field) => !sameValue(draftOrigin?.[field], draft.values[field]))
        : [];
      replace({
        ...state,
        draft,
        dirtyFields,
        validationErrors: { fieldErrors: {}, formErrors: [] },
      });
    },
    setDraftReason: (reason) => {
      if (!state.draft) throw new Error('System settings draft is not active.');
      replace({
        ...state,
        draft: deepFreeze({ ...state.draft, reason }),
        validationErrors: { fieldErrors: {}, formErrors: [] },
      });
    },
    setValidationErrors: (validationErrors) => replace({ ...state, validationErrors }),
    setPending: (pending) => replace({ ...state, pending }),
    recordFeedback: (lastFeedback) => {
      if (lastFeedback.ok) draftOrigin = undefined;
      replace({
        ...state,
        pending: false,
        ...(lastFeedback.ok ? { draft: undefined, ...clearDraftState() } : {}),
        lastFeedback,
      });
    },
    discardDraft: () => {
      draftOrigin = undefined;
      replace({
        ...state,
        draft: undefined,
        ...clearDraftState(),
      });
    },
    reset: () => {
      draftOrigin = undefined;
      replace(initialState());
    },
  });
}
