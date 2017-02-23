import { createStore, applyMiddleware } from 'redux';
import thunk from 'redux-thunk';
import reducer from 'reducers';

const middlewares = [thunk];

if (__DEV__) {
  const createLogger = require('redux-logger');
  middlewares.push(createLogger());
}

const createStoreWithMiddleware = applyMiddleware(...middlewares)(createStore);

export default createStoreWithMiddleware(reducer);
