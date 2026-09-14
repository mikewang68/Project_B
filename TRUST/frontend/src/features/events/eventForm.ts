export const localTime = (date = new Date()) =>
  new Date(date.getTime() - date.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
export const emptyForm = () => ({
  sourceSystem: "MANUAL",
  sourceEventId: "",
  eventType: "ARRIVAL",
  businessObjectId: "",
  batchId: "",
  occurredAt: localTime(),
  quantity: "",
  unit: "吨",
  location: "",
  supplier: "",
  receiver: "",
  handoverId: "",
  bundleText: "",
  relatedBatchText: "",
  relatedEventText: "",
  note: "",
});
export const split = (v: string) =>
  v
    .split(/[，,\n]/)
    .map((s) => s.trim())
    .filter(Boolean);
