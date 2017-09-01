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
        if (response.status === 503) {   //Handle server time out separately
          return Promise.reject({status: response.status, response: {message: 'Request timed out'}})
        }
        return response.json().then(response =>       //Handling a non ok json response
          Promise.reject({status: response.status, response: response})
        );
      },
    ).catch(error => {
      console.log('Error while fetching response : ', error);    //Handling Promise rejects or any exceptions from previous then
      return Promise.reject(error);
    });
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
