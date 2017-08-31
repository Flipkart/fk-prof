import { objectToQueryParams } from './UrlUtils';
import fetch from 'isomorphic-fetch';

const defaultConfig = {
  headers: {
    'Content-Type': 'application/json',
  },
  credentials: 'same-origin',
  redirect: 'follow',
};

export const GET = 'GET';
export const POST = 'POST';
export const PUT = 'PUT';
export const DELETE = 'DELETE';

function fireRequest (url, config) {
  const conf = Object.assign({ ...defaultConfig }, config);
  return fetch(url, conf)
    .then((response) => {
      if (response.ok) {
        return Promise.resolve(response.json());
      } else if(response.status >= 400){
        const error = new Error();
        error.status = response.status;
        error.response = response.statusText;
        throw error;
      }
      return response.json().then(error =>{
        return Promise.reject({ status: response.status, response: error });});
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
