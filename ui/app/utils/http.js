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
        }
        if (response.status === 503) {   //Handle server time out separately
          const err = new Error();
          err.response = {message: 'Request timed out'};
          throw err;
        }
        return response.json().then(response => {       //Handling a non ok json response
          console.log('Fetch received a non ok response : ', response);
          return Promise.reject({status: response.status, response: response});
        });
      },
    ).catch(error => {
      console.log('Error while parsing fetch response : ', JSON.stringify(error));    //Handling json parsing exceptions
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
