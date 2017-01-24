import React from 'react';
import ReactDOM from 'react-dom';
import {
  Route,
  Router,
  browserHistory,
} from 'react-router';
import { Provider } from 'react-redux';

import 'react-select/dist/react-select.css';
import 'react-datetime/css/react-datetime.css';
import 'material-design-lite/material.min';
import 'material-design-lite/material.css';

import store from './store';

import Root from 'components/RootComponent';

const routes = (
  <Route path="/" component={Root} />
);

ReactDOM.render(
  <Provider store={store}>
    <Router history={browserHistory}>
      {routes}
    </Router>
  </Provider>,
  document.getElementById('root'),
);
