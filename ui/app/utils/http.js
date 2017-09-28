import { objectToQueryParams } from './UrlUtils';
import fetch from 'isomorphic-fetch';

const defaultConfig = {
  headers: {
    'Content-Type': 'application/json',
  },
  credentials: 'same-origin',
  redirect: 'follow',
};

function fireRequest (url, config) {
  const conf = Object.assign({ ...defaultConfig }, config);
  return fetch(url, conf)
    .then((response) => {
      if (response.ok) {
        return Promise.resolve(response.json());
      }
      return response.json().then(error =>
        Promise.reject({ status: response.status, response: error }));
    },
  ).catch(error => Promise.reject(error));
}

export default {
  get (url, requestParams, config) {
    const urlWithQuery = url + objectToQueryParams(requestParams);
    return fireRequest(urlWithQuery, Object.assign({
      method: 'get',
    }, config));
  },
  put (url, data, config) {
    return fireRequest(url, Object.assign({
      method: 'put',
      body: JSON.stringify(data),
    }, config));
  },
  post (url, data, config = {}) {
    return fireRequest(url, Object.assign({
      method: 'post',
      body: config.formData ? data : JSON.stringify(data),
    }, config));
  },
  delete (url, config) {
    return fireRequest(url, Object.assign({
      method: 'delete',
    }, config));
  },
};
