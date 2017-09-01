import React from 'react';
import ReactDOM from 'react-dom';
import {
  Route,
  Router,
  IndexRedirect,
  browserHistory,
  Redirect,
} from 'react-router';
import { Provider } from 'react-redux';

import 'react-select/dist/react-select.css';
import 'react-datetime/css/react-datetime.css';
import 'material-design-lite/material.min';
import 'material-design-lite/material.css';

import store from './store';

import Root from 'components/RootComponent';
import App from 'components/AppComponent';
import CPUSampling from 'components/CPUSamplingComponent';
import AggregatedProfileDataContainer from 'components/AggregatedProfileDataContainer';

import './assets/styles/global.css';

const routes = (
  <Route path="/" component={Root}>
    <IndexRedirect to="/profiles" />
    <Redirect from="/profiler/profile-data/:traceName" to="/profile/profile-data/:traceName"/>    //To stay backward compatible for old urls
    <Redirect from="/profiler" to="/profiles"/>                  //To stay backward compatible for old urls
    <Route path="/profiles" component={App}>
      <Route path="/profile/profile-data/:traceName" component={AggregatedProfileDataContainer} />
    </Route>
    <Route path="/policies" component={App}/>
    <Route path="/work-type/cpu_sample_work/:traceName" component={CPUSampling} />
  </Route>
);

ReactDOM.render(
  <Provider store={store}>
    <Router history={browserHistory}>
      {routes}
    </Router>
  </Provider>,
  document.getElementById('root'),
);
