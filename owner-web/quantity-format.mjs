export function formatQuantity(quantity, unitLabel) {
  const value = String(quantity);
  return typeof unitLabel === 'string' && unitLabel.length > 0
    ? `${value} ${unitLabel}`
    : `${value} (unit not reported)`;
}
