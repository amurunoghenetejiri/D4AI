/** Image OCR helper. The standalone D4AI app ships without the D4EXAM OCR
 * pipeline; images are still sent to the vision model directly. */
export async function runImageOcr(_blob: Blob): Promise<string> {
  return "";
}
