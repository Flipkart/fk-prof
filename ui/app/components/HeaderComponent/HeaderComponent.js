import React from 'react';
import {Link} from "react-router";
import fk_logo from "../../assets/fk-prof-logo.svg";

export default class Header extends React.Component {
  constructor(props) {
    super(props);
  }

  render() {
    let profileActive = "";
    let policyActive = "";
    if (this.props.isPoliciesPage) {
      policyActive = " is-active";
    } else {
      profileActive = " is-active";
    }
    const reqKeys = ['app', 'cluster', 'proc'];
    const queryObject = Object.assign({}, this.props.queryParams);
    Object.keys(queryObject).filter(k => !reqKeys.includes(k)).forEach(k => delete queryObject[k]);
    return (
      <div className="mdl-layout__header mdl-layout__header--transparent">
        <div className="mdl-layout__tab-bar mdl-js-ripple-effect" style={{height: '48px', width: '100%'}}>
          <div className="mdl-layout--large-screen-only" style={{height: '90%'}}>
            <Link to={loc => ({pathname: '/profiles', query: ''})}
                  style={{textDecoration: "none"}}>
              <img src={fk_logo} className="mdl-shadow--4dp "
                   style={{marginTop: '5px', height: '80%', borderRadius: '2px'}}/>
              <span style={{verticalAlign: '-3px', margin: 'auto 20px auto 5px', color: 'white'}}>FK Profiler</span>
            </Link>
          </div>
          <Link className={"mdl-layout__tab" + profileActive}
                to={loc => ({pathname: '/profiles', query: queryObject})}
                style={{textDecoration: "none", textTransform: 'capitalize'}}>
            Profiles
          </Link>
          <Link className={"mdl-layout__tab" + policyActive}
                to={loc => ({pathname: '/policies', query: queryObject})}
                style={{textDecoration: "none", textTransform: 'capitalize'}}>
            Policies
          </Link>
        </div>
      </div>
    );
  }
}
