export function objectToQueryParams (params = {}) {
  return Object.keys(params)
    .map(k => `${encodeURIComponent(k)}=${encodeURIComponent(params[k])}`)
    .join('&');
}
