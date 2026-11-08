export function quotaLabel(bucket: string): string {
  return ({ five_hour: 'Five-hour session', seven_day: 'Weekly — all models', seven_day_fable: 'Weekly — Fable' } as Record<string, string>)[bucket] || bucket;
}
