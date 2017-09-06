import http from '../utils/http';

export function postAfterDelay(delay, url, body) {
  return new Promise(
    (resolve, reject) => window.setTimeout(() =>
        http.post(url, body).then((res) => resolve(res), (err) => reject(err))
      , delay));
}

export default function postWithRetryOnAccept(url, body, numRetries) {
  return postAfterDelay(1000, url, body).then(
    (resp) => {
      if (resp.status === 202) {
        if (numRetries > 0) {
          console.log('retrying, numRetry left: ', numRetries);
          return Promise.resolve(postWithRetryOnAccept(url, body, numRetries - 1));
        } else {
          return Promise.reject({response: {message: 'No more retries left'}});
        }
      } else {
        return Promise.resolve(resp);
      }
    }, (err) => {console.log(err); return Promise.reject(err);}
  );
}
